package com.yunx.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.download.ChunkDebug
import com.yunx.app.data.download.DownloadDebugLog
import com.yunx.app.data.download.DownloadResourceDebug
import com.yunx.app.data.download.DownloadStats
import com.yunx.app.data.download.MagnetLink
import com.yunx.app.ui.BackHandler
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.viewmodel.DownloadViewModel
import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 调试页刷新间隔（毫秒）：拉取式快照，页面关掉即停 */
private const val DEBUG_REFRESH_MS = 1000L

/** 「疑似卡死」判定：已跑够久、瞬时速度低于阈值且还没下完（与 DownloadManager 的抢占下限同一口径 12KB/s） */
private const val STUCK_MIN_AGE_MS = 30_000L
private const val STUCK_MIN_BPS = 12 * 1024L

/**
 * 下载调试页（下载页右键任务 → 「调试信息」）。
 *
 * 需先在「设置 → 关于 YunX-OS → 长按 → 开发调试 → 下载调试」打开开关；否则右键菜单里不会出现入口。
 *
 * ★ 数据是**拉取式**的：每秒拉一次，离开页面即取消 —— 不打开本页时对下载没有任何额外开销。
 *
 * 桌面布局差异（手机端是单列长滚动，桌面窗口宽、竖直空间反而更紧）：
 * - 顶部一行放「返回 + 标题 + 操作」，操作按钮不再占一整个列表项；
 * - 正文**左右分栏**：左栏（全局资源 + 线程表）／右栏（实时日志跟随），两栏各自独立滚动；
 * - 日志用等宽字体，便于对齐阅读时间戳与序号。
 */
@Composable
internal fun DownloadDebugScreen(
    task: DownloadTaskEntity,
    stats: DownloadStats?,
    viewModel: DownloadViewModel,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var chunks by remember { mutableStateOf<List<ChunkDebug>>(emptyList()) }
    var resources by remember { mutableStateOf<DownloadResourceDebug?>(null) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }

    BackHandler(onBack = onBack)

    // 每秒轮询（LaunchedEffect 随本页离开组合树自动取消）
    LaunchedEffect(task.id) {
        while (true) {
            chunks = viewModel.chunkDebugSnapshot(task.id)
            resources = viewModel.resourceDebugSnapshot()
            logLines = viewModel.debugLogEntries(task.id)
            delay(DEBUG_REFRESH_MS)
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部一行：返回 + 标题 +（右侧）操作按钮；不额外占一个列表项
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = "下载调试",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    DesktopActions.copyToClipboard(
                        buildSnapshotText(task, stats, chunks, resources, DownloadDebugLog.environmentSnapshot())
                    )
                    SnackbarController.show("调试快照已复制")
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("复制快照")
                }
                TextButton(onClick = {
                    // 写文件要离开主线程（与设置页导出同一口径）
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { DownloadDebugLog.exportToFile(task) }
                        if (file != null && DesktopActions.revealFile(file.absolutePath)) {
                            SnackbarController.show("已导出完整下载日志（${file.name}）")
                        } else {
                            SnackbarController.show("导出失败，请稍后重试")
                        }
                    }
                }) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("导出完整日志")
                }
            }

            // 任务卡：与下载页同一套文案与进度口径
            DebugTaskCard(
                task = task,
                stats = stats,
                onPause = onPause,
                onResume = onResume,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 左右分栏：左 = 资源 + 线程表，右 = 实时日志
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ThreadsColumn(
                    chunks = chunks,
                    resources = resources,
                    totalWorkers = stats?.chunkCount ?: 0,
                    onForceReconnect = { chunk ->
                        val ok = viewModel.forcePreemptChunk(task.id, chunk.key)
                        SnackbarController.show(
                            if (ok) "已标记 ${chunk.key} 断开换连接续传" else "该分片已结束，无法重连"
                        )
                    },
                    modifier = Modifier.weight(0.46f).fillMaxHeight()
                )
                // 分隔线（用 Box 画，避免依赖 VerticalDivider 的可用性）
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                LogsColumn(logLines = logLines, modifier = Modifier.weight(0.54f).fillMaxHeight())
            }
        }
    }
}

/** 左栏：全局资源 + 线程表（线程表自身滚动，资源面板固定在上方） */
@Composable
private fun ThreadsColumn(
    chunks: List<ChunkDebug>,
    resources: DownloadResourceDebug?,
    totalWorkers: Int,
    onForceReconnect: (ChunkDebug) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(start = 16.dp, end = 12.dp, bottom = 16.dp)) {
        SectionTitle("全局资源")
        ResourcePanel(resources)
        Spacer(modifier = Modifier.height(10.dp))
        SectionTitle("线程（每个在飞分片在干什么）")
        val summary = when {
            chunks.isEmpty() ->
                "当前没有在飞分片（未开始 / 已暂停 / 已完成，或该任务走的是单流 / Gopeed 引擎下载）。"
            totalWorkers > 0 ->
                "在飞 ${chunks.size} 路 · 空闲 ${(totalWorkers - chunks.size).coerceAtLeast(0)} 路（共 $totalWorkers 个 worker）"
            else -> "在飞 ${chunks.size} 路"
        }
        HintText(summary)
        Spacer(modifier = Modifier.height(6.dp))
        if (chunks.isEmpty()) return
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            items(chunks, key = { it.key }) { chunk ->
                ChunkRow(chunk = chunk, onForceReconnect = { onForceReconnect(chunk) })
            }
        }
    }
}

/** 右栏：实时日志（跟随尾部滚动，用户往上翻看时不抢滚动位置） */
@Composable
private fun LogsColumn(logLines: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // ★ 「实时跟随」只在用户**本来就停在底部**时才生效：往上翻看历史日志时绝不再把页面拽到底部。
    //   判定只在用户主动滚动时更新 —— 新日志追加也会让 canScrollForward 短暂变 true，
    //   若不加 isScrollInProgress 条件就会被误判成「用户翻上去了」而永久停止跟随。
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (scrolling) follow = !canScrollForward
            }
    }
    val rows = logLines.size.coerceAtLeast(1)
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty() && follow) {
            runCatching { listState.animateScrollToItem((rows - 1).coerceAtLeast(0)) }
        }
    }
    Column(modifier = modifier.padding(start = 12.dp, end = 16.dp, bottom = 16.dp)) {
        SectionTitle("实时日志（主要变更节点 / 错误节点）")
        HintText("共 ${logLines.size} 条 · 每 ${DEBUG_REFRESH_MS / 1000} 秒刷新 · 用等宽字体对齐时间戳")
        Spacer(modifier = Modifier.height(6.dp))
        if (logLines.isEmpty()) {
            HintText("暂无日志：该任务创建时「下载调试」未开启，或开关刚被关闭（关闭会清空日志）。")
            return
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(logLines.size) { i ->
                Text(
                    text = logLines[i],
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 任务卡：文件名 / 状态 / 进度条 / 实时速度 / 暂停-继续，口径与下载页一致 */
@Composable
private fun DebugTaskCard(
    task: DownloadTaskEntity,
    stats: DownloadStats?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 合并阶段（-1 = 不在合并）：合并时下载早已 100%，进度条改显示合并百分比（与下载页同一口径）
    val mergePercent = stats?.mergePercent ?: -1
    val merging = mergePercent >= 0
    val fraction = when {
        merging -> mergePercent / 100f
        task.totalSize > 0 -> (minOf(task.downloadedSize, task.totalSize).toFloat() / task.totalSize).coerceIn(0f, 1f)
        else -> 0f
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        // 桌面横向空间充足：图标 / 名称 / 状态 / 进度 / 实时速度 / 操作 全放在一行，省下竖直空间给下面的表
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (merging) "合并中 · $mergePercent%" else taskStatusLine(task),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.width(320.dp)) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (task.status == DownloadTaskEntity.STATUS_FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                Spacer(modifier = Modifier.height(4.dp))
                val liveText = when {
                    merging -> "正在合并分片 · $mergePercent%"
                    else -> stats
                        ?.takeIf { it.speed > 0 }
                        ?.let { "${fmtSpeed(it.speed)} · 剩余 ${fmtRemain(it.remainMillis)} · ${it.chunkCount} 线程" }
                }
                Text(
                    text = liveText ?: progressLine(task),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (liveText != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            when (task.status) {
                DownloadTaskEntity.STATUS_DOWNLOADING,
                DownloadTaskEntity.STATUS_PENDING -> IconButton(onClick = onPause) {
                    Icon(Icons.Outlined.Pause, contentDescription = "暂停", tint = MaterialTheme.colorScheme.primary)
                }
                DownloadTaskEntity.STATUS_PAUSED -> IconButton(onClick = onResume) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "继续", tint = MaterialTheme.colorScheme.primary)
                }
                DownloadTaskEntity.STATUS_FAILED -> IconButton(onClick = onResume) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "重试", tint = MaterialTheme.colorScheme.error)
                }
                else -> Unit // 已完成：调试页不提供「打开/删除」，回下载页操作
            }
        }
    }
}

/** 全局资源面板：在飞配额占用 / 活跃任务数 / 线程池 / 堆内存 */
@Composable
private fun ResourcePanel(resources: DownloadResourceDebug?) {
    if (resources == null) {
        HintText("读取中…")
        return
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            InfoRow("在飞分片", "${resources.inflightUsed} / ${resources.inflightCap}")
            InfoRow("活跃任务", resources.activeTasks.toString())
            InfoRow("线程池", "线程 ${resources.poolSize} · 忙 ${resources.poolActive} · 队列 ${resources.poolQueue}")
            InfoRow(
                "堆内存",
                "${resources.heapUsed / 1024 / 1024} MB / ${resources.heapMax / 1024 / 1024} MB"
            )
        }
    }
}

/** 单个在飞分片：key / 区间 / 已收 / 瞬时速度 / 已跑时长 / 抢占情况 + 「重连」 */
@Composable
private fun ChunkRow(chunk: ChunkDebug, onForceReconnect: () -> Unit) {
    val percent = if (chunk.size > 0) (chunk.received * 100 / chunk.size).toInt() else 0
    val preemptNote = buildString {
        if (chunk.preemptCount > 0) append(" · 已抢占 ${chunk.preemptCount} 次")
        if (chunk.preempting) append(" · 待换连接")
    }
    // 「疑似卡死」：跑得够久、又慢得离谱且没下完（多半是连接被 CDN 挂住，可用右侧「重连」换一条）
    val stuck = chunk.elapsedMs >= STUCK_MIN_AGE_MS &&
        chunk.bps < STUCK_MIN_BPS &&
        chunk.received < chunk.size
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${chunk.key}　worker #${chunk.worker}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "区间 ${chunk.start}-${chunk.start + chunk.size - 1} · " +
                        "已收 ${fmtSize(chunk.received)}/${fmtSize(chunk.size)}（$percent%）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "瞬时 ${fmtSpeed(chunk.bps)} · 已跑 ${chunk.elapsedMs / 1000}s$preemptNote" +
                        if (stuck) " · 疑似卡死" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stuck) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onForceReconnect) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("重连")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp)
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

/** 调试快照文本：任务字段 + 当前环境 + 在飞分片表 + 全局资源（复制到剪贴板用；直链已脱敏） */
private fun buildSnapshotText(
    task: DownloadTaskEntity,
    stats: DownloadStats?,
    chunks: List<ChunkDebug>,
    resources: DownloadResourceDebug?,
    env: String
): String = buildString {
    appendLine("YunX-OS 下载调试快照")
    appendLine("任务 id：${task.id}　文件名：${task.fileName}")
    appendLine("状态：${DownloadTaskEntity.statusText(task.status)}　平台：${task.platform.ifBlank { "-" }}")
    appendLine("执行器：${task.engineTaskId.ifBlank { "内置分片下载器" }}")
    appendLine("大小：${task.downloadedSize} / ${task.totalSize} 字节")
    appendLine("环境：$env")
    if (stats != null) {
        appendLine("实时：${fmtSpeed(stats.speed)}　剩余 ${fmtRemain(stats.remainMillis)}　${stats.chunkCount} 线程")
    }
    if (task.errorMsg.isNotBlank()) appendLine("失败原因：${task.errorMsg}")
    appendLine()
    appendLine("---------- 在飞分片（${chunks.size}）----------")
    if (chunks.isEmpty()) {
        appendLine("（无）")
    } else {
        chunks.forEach {
            appendLine(
                "${it.key} | worker#${it.worker} | 区间 ${it.start}-${it.start + it.size - 1} | " +
                    "已收 ${it.received}/${it.size} | 瞬时 ${it.bps}B/s | 已跑 ${it.elapsedMs}ms | " +
                    "抢占 ${it.preemptCount} 次${if (it.preempting) "（待换连接）" else ""}"
            )
        }
    }
    appendLine()
    appendLine("---------- 全局资源 ----------")
    if (resources == null) {
        appendLine("（读取中）")
    } else {
        appendLine("在飞分片：${resources.inflightUsed}/${resources.inflightCap}")
        appendLine("活跃任务：${resources.activeTasks}")
        appendLine("分片 IO 线程池：线程 ${resources.poolSize} · 忙 ${resources.poolActive} · 队列 ${resources.poolQueue}")
        appendLine("堆内存：${resources.heapUsed / 1024 / 1024}MB / ${resources.heapMax / 1024 / 1024}MB")
    }
}

private fun taskStatusLine(task: DownloadTaskEntity): String {
    // 磁力任务在引擎拿到元数据之前 totalSize 一直是 0：这时按进度显示会变成「下载中」却没有任何数字，
    // 看起来像卡死。BT 的元数据要等 DHT/tracker 找到 peer，慢起来是分钟级，明说在解析更诚实。
    if (task.status == DownloadTaskEntity.STATUS_DOWNLOADING &&
        task.totalSize <= 0 &&
        MagnetLink.isMagnet(task.url)
    ) {
        return "正在解析磁力（等待元数据，可能需要一两分钟）"
    }
    val status = DownloadTaskEntity.statusText(task.status)
    return if (task.totalSize > 0) {
        val shown = minOf(task.downloadedSize, task.totalSize)
        "$status · ${fmtSize(shown)} / ${fmtSize(task.totalSize)}"
    } else {
        status
    }
}

private fun progressLine(task: DownloadTaskEntity): String {
    if (task.totalSize <= 0) return ""
    val shown = minOf(task.downloadedSize, task.totalSize)
    val percent = (shown * 100 / task.totalSize).toInt().coerceIn(0, 100)
    return "已下载 ${fmtSize(shown)} / ${fmtSize(task.totalSize)} · $percent%"
}

private fun fmtSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024
        i++
    }
    return String.format("%.1f %s", value, units[i])
}

private fun fmtSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "0 B/s"
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSec.toDouble()
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024
        i++
    }
    return String.format("%.1f %s", value, units[i])
}

private fun fmtRemain(millis: Long): String {
    if (millis < 0) return "计算中"
    val sec = millis / 1000
    return when {
        sec < 60 -> "${sec}秒"
        sec < 3600 -> "${sec / 60}分${sec % 60}秒"
        else -> "${sec / 3600}时${(sec % 3600) / 60}分"
    }
}
