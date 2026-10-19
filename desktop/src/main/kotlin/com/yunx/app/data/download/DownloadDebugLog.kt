package com.yunx.app.data.download

import com.yunx.app.APP_VERSION
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.util.LogRedactor
import java.io.File
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 按任务记录事件流：序号、网络环境与切换、worker 编号与线程池、抢占编号与结果、合并阶段（顺序 / 校验 / 清理 / 重试）。
 *
 * 与 [com.yunx.app.util.DiagnosticLog]（按模块的全局诊断日志、落盘轮转）不同：这里是**按下载任务**记录的事件流，
 * 面向「这个任务为什么慢 / 为什么失败」的排查 —— 调试页里能实时跟随，也能整体导出成文件。
 *
 * ★ 三条硬约束（改之前先读）：
 * 1. **关闭即清空**：[setEnabled] 传 false 会把所有任务的事件全部丢掉 ⇒ 设置页关开关前有二次确认弹窗；
 * 2. **只在开启时记录**：[record] 第一行就是 `if (!enabled) return`，所以调用点可以随手埋，
 *    关闭时零开销、也不会留下任何新日志；
 * 3. **只活在内存**：不落盘（进程被杀即丢）。关闭必须能立刻清空，落盘反而还要处理残留文件的清理，
 *    调试日志不值得为它引入一整套文件生命周期。
 *
 * 桌面差异（相对上游 Android 版）：
 * - 无 `Context` 参数：偏好走 [SettingsRepository]，应用版本取生成的 `APP_VERSION`；
 * - 网络环境用 [NetworkInterface]（网卡名 / MTU）替代 Android 的 `ConnectivityManager`，
 *   并额外记**代理模式**——桌面端下载速度异常最常见的原因就是代理没走对；
 * - 无「省电模式」「蜂窝代际」这类移动端概念。
 */
object DownloadDebugLog {

    /** 单任务最多保留的事件条数：超出丢最旧的（调试只关心「最近发生了什么」） */
    private const val MAX_ENTRIES_PER_TASK = 2000

    /** 最多保留多少个任务的事件：超出丢最早登记的那个（长时间挂着开关也不至于无限涨内存） */
    private const val MAX_TASKS = 50

    /** 导出文件的目录（与 LogExporter 同一处，用户可直接在资源管理器里打开） */
    private const val EXPORT_DIR = "logs"

    private val enabled = AtomicBoolean(false)

    /** 所有可变状态由这把锁保护（事件低频写，不值得给每个任务单独加锁） */
    private val lock = Any()

    /** taskId → 事件行（时间戳已格式化，直接可读、可直接导出） */
    private val logs = HashMap<Long, ArrayDeque<String>>()

    /** taskId → 该任务的事件序号（自增，从 1 开始）：日志量大时便于引用某一条 */
    private val seqs = HashMap<Long, Int>()

    /** 任务登记顺序：超过 [MAX_TASKS] 时按此丢最早的 */
    private val order = ArrayDeque<Long>()

    /** 时间戳格式：只在锁内使用（SimpleDateFormat 非线程安全），事件低频，竞争可忽略 */
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    fun isEnabled(): Boolean = enabled.get()

    /**
     * 开关下载调试。**传 false 会清空全部日志**（用户已确认该行为，设置页有二次确认）。
     * 立即生效，不需要重启应用。
     */
    fun setEnabled(value: Boolean) {
        enabled.set(value)
        if (!value) clear()
    }

    /** 启动时按持久化开关装上（与 DiagnosticLog.install 同款；必须在任何下载开始前调用） */
    fun install() {
        setEnabled(SettingsRepository().downloadDebug)
    }

    // ---------- 网络环境（下载很依赖网络环境，调试日志要把这一层也记下来）----------

    /**
     * 网络特征串（便宜、只含「网卡名 + MTU」）：变化即说明换了网络，
     * 供看门狗做「是否切换网络」的检测。
     */
    fun networkSignature(): String = runCatching {
        val names: List<String> = activeInterfaceNames()
        if (names.isEmpty()) "无网络" else names.sorted().joinToString(",")
    }.getOrDefault("未知")

    /**
     * 采集当前网络环境：活动网卡名与代理模式。
     * 全程 runCatching：任何读取失败都只退化成「未知」，绝不影响下载。
     */
    fun environmentSnapshot(): String {
        val parts: MutableList<String> = mutableListOf()
        val names: List<String> = activeInterfaceNames()
        val netText: String = if (names.isEmpty()) "无网络" else names.joinToString("+")
        parts.add("网络=$netText")
        runCatching {
            val s = SettingsRepository()
            val proxyText: String = when (s.proxyMode) {
                SettingsRepository.PROXY_MODE_SYSTEM -> "系统代理"
                SettingsRepository.PROXY_MODE_MANUAL -> "手动 " + s.proxyHost + ":" + s.proxyPort
                else -> "直连"
            }
            parts.add("代理=$proxyText")
        }
        return parts.joinToString(" ")
    }

    /** 活动网卡名（已启用、非回环）；读取失败返回空表 */
    private fun activeInterfaceNames(): List<String> {
        val result: MutableList<String> = mutableListOf()
        val all = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return result
        for (nic in all) {
            val up = runCatching { nic.isUp }.getOrDefault(false)
            val loopback = runCatching { nic.isLoopback }.getOrDefault(false)
            if (!up || loopback) continue
            result.add(nic.name ?: "?")
        }
        return result
    }

    /**
     * 记一条事件。[node] 是「发生了什么」（入队 / 开始 / 分片规划 / 抢占 / 重试 / 合并 / 完成 / 失败…），
     * [detail] 是补充信息。★ [detail] 必须**已经脱敏**：URL 走 [LogRedactor.url]，
     * 绝不写入 Cookie / token 原文。
     */
    fun record(taskId: Long, node: String, detail: String = "") {
        if (!enabled.get()) return
        synchronized(lock) {
            val seq = (seqs[taskId] ?: 0) + 1
            seqs[taskId] = seq
            val stamp = timeFormat.format(Date())
            val head = "#$seq $stamp | $node"
            val line = if (detail.isBlank()) head else "$head | $detail"
            val deque = logs[taskId] ?: ArrayDeque<String>().also {
                logs[taskId] = it
                order.addLast(taskId)
                while (order.size > MAX_TASKS) {
                    val oldest = order.removeFirst()
                    logs.remove(oldest)
                    seqs.remove(oldest)
                }
            }
            deque.addLast(line)
            while (deque.size > MAX_ENTRIES_PER_TASK) deque.removeFirst()
        }
    }

    /** 读某任务的全部事件（时间正序），供调试页实时跟随 */
    fun entries(taskId: Long): List<String> = synchronized(lock) {
        logs[taskId]?.toList() ?: emptyList()
    }

    /** 任务被删除时丢掉它的事件（留着也永远不会再被查看） */
    fun drop(taskId: Long) {
        synchronized(lock) {
            logs.remove(taskId)
            seqs.remove(taskId)
            order.remove(taskId)
        }
    }

    /** 清空全部日志（关闭开关时调用） */
    fun clear() {
        synchronized(lock) {
            logs.clear()
            seqs.clear()
            order.clear()
        }
    }

    /**
     * 该任务的**完整下载日志**文本：应用/系统信息 + 任务原始字段（直链脱敏）+ 事件流。
     * 「主要变更节点 / 错误节点」就是事件流里各条 [record] 的结果。
     */
    fun buildExportText(task: DownloadTaskEntity): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return buildString {
            appendLine("YunX-OS 下载调试日志")
            appendLine("导出时间：${sdf.format(Date())}")
            appendLine("应用版本：$APP_VERSION")
            appendLine("系统：${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            appendLine("Java：${System.getProperty("java.version")}")
            appendLine()
            appendLine("========== 任务信息 ==========")
            appendLine("任务 id：${task.id}")
            appendLine("文件名：${task.fileName}")
            appendLine("状态：${DownloadTaskEntity.statusText(task.status)}")
            appendLine("平台：${task.platform.ifBlank { "-" }}")
            appendLine("总大小：${task.totalSize} 字节")
            appendLine("已下载：${task.downloadedSize} 字节")
            appendLine("平均速度：${task.avgSpeed} B/s")
            appendLine("执行器：${task.engineTaskId.ifBlank { "内置分片下载器" }}")
            appendLine("直链：${LogRedactor.url(task.url)}")
            appendLine("保存路径：${task.savePath.ifBlank { "-" }}")
            if (task.errorMsg.isNotBlank()) appendLine("失败原因：${task.errorMsg}")
            appendLine()
            appendLine("========== 事件流（主要变更节点 / 错误节点）==========")
            val list = entries(task.id)
            if (list.isEmpty()) appendLine("（无：任务创建时「下载调试」未开启）") else list.forEach { appendLine(it) }
        }
    }

    /** 把完整下载日志写到 <缓存目录>/logs 下并返回文件（失败返回 null） */
    fun exportToFile(task: DownloadTaskEntity): File? = runCatching {
        val dir = File(com.yunx.app.AppContext.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "yunx_download_debug_${task.id}_$stamp.txt")
        file.writeText(buildExportText(task))
        file
    }.getOrNull()
}
