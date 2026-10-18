package com.yunx.app.util

import com.sun.jna.platform.win32.Crypt32Util
import com.yunx.app.AppContext
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 一键从本机浏览器导入网盘登录 Cookie（方案 A）。
 *
 * - Windows Chromium 系（Chrome / Edge / 360 / QQ / 搜狗 / Opera / Brave 等）：
 *   Network\Cookies（SQLite）中的 encrypted_value 为 "v10" 前缀 + Windows DPAPI 加密载荷，
 *   用 CryptUnprotectData 解密（同一 Windows 用户下可解）。
 *   "v20" 前缀为「应用绑定加密」（Chrome 127+ / 较新 Edge），本应用无法解密 → 跳过该 Cookie。
 * - macOS Chromium：Cookie 用浏览器的 Keychain Safe Storage 密钥 + AES-CBC 加密；
 *   Firefox 的 cookies.sqlite 中 value 为明文，直接读取。
 */
object BrowserCookieImporter {

    private const val TAG = "YunX-Cookie"

    enum class Kind { CHROMIUM, FIREFOX }

    data class BrowserInfo(
        val id: String,
        val displayName: String,
        val kind: Kind,
        val profileDirs: List<File>
    )

    data class FoundCookies(
        val browserName: String,
        val cookie: String,
        val count: Int
    )

    /** 平台 → 需要匹配的 Cookie 域名后缀 */
    fun domainSuffixes(platform: String): List<String> = when (platform) {
        "QUARK" -> listOf(".quark.cn")
        "UC" -> listOf(".uc.cn")
        "BAIDU" -> listOf(".baidu.com")
        "C139" -> listOf("mail.10086.cn", "yun.139.com", ".10086.cn")
        "PAN115" -> listOf(".115.com", ".115cdn.com")
        else -> emptyList()
    }

    /** 发现本机已安装浏览器的 Cookie 库目录（存在 cookies 文件才算） */
    fun discoverBrowsers(): List<BrowserInfo> {
        val osName = System.getProperty("os.name", "").lowercase()
        if (osName.contains("mac")) return discoverMacBrowsers()
        if (!osName.contains("win")) return emptyList()

        val local = System.getenv("LOCALAPPDATA")?.let { File(it) } ?: return emptyList()
        val roaming = System.getenv("APPDATA")?.let { File(it) } ?: return emptyList()
        val out = mutableListOf<BrowserInfo>()

        // Chromium 系：<root>/Network/Cookies
        val chromiumCandidates = listOf(
            "Chrome" to listOf(File(local, "Google\\Chrome\\User Data")),
            "Edge" to listOf(File(local, "Microsoft\\Edge\\User Data")),
            "360极速浏览器" to listOf(File(local, "360Chrome\\Chrome\\User Data")),
            "360安全浏览器" to listOf(
                File(roaming, "360se6\\User Data"),
                File(roaming, "360se\\User Data"),
                File(local, "360se6\\User Data")
            ),
            "QQ浏览器" to listOf(File(local, "Tencent\\QQBrowser\\User Data")),
            "搜狗浏览器" to listOf(
                File(roaming, "SogouExplorer\\User Data"),
                File(local, "SogouExplorer\\User Data")
            ),
            "Opera" to listOf(File(roaming, "Opera Software\\Opera Stable")),
            "Brave" to listOf(File(local, "BraveSoftware\\Brave-Browser\\User Data")),
            "Vivaldi" to listOf(File(local, "Vivaldi\\User Data"))
        )
        for ((name, roots) in chromiumCandidates) {
            val existing = roots.flatMap(::chromiumProfiles)
            if (existing.isNotEmpty()) {
                out += BrowserInfo("chromium-$name", name, Kind.CHROMIUM, existing)
            }
        }

        // Firefox：<Profiles>/xxx/cookies.sqlite
        val ffRoot = File(roaming, "Mozilla\\Firefox\\Profiles")
        if (ffRoot.isDirectory) {
            val profiles = ffRoot.listFiles { f -> f.isDirectory && File(f, "cookies.sqlite").exists() }
                ?.toList() ?: emptyList()
            if (profiles.isNotEmpty()) {
                out += BrowserInfo("firefox", "Firefox", Kind.FIREFOX, profiles)
            }
        }
        return out
    }

    private fun discoverMacBrowsers(): List<BrowserInfo> {
        val home = File(System.getProperty("user.home"))
        val appSupport = File(home, "Library/Application Support")
        val out = mutableListOf<BrowserInfo>()
        val chromiumCandidates = listOf(
            "Chrome" to File(appSupport, "Google/Chrome/User Data"),
            "Edge" to File(appSupport, "Microsoft Edge/User Data"),
            "Brave" to File(appSupport, "BraveSoftware/Brave-Browser/User Data"),
            "Vivaldi" to File(appSupport, "Vivaldi/User Data"),
            "Opera" to File(appSupport, "com.operasoftware.Opera")
        )
        for ((name, root) in chromiumCandidates) {
            val profiles = chromiumProfiles(root)
            if (profiles.isNotEmpty()) {
                out += BrowserInfo("mac-chromium-$name", name, Kind.CHROMIUM, profiles)
            }
        }

        val ffRoot = File(appSupport, "Firefox/Profiles")
        val profiles = ffRoot.listFiles { f ->
            f.isDirectory && File(f, "cookies.sqlite").isFile
        }?.toList().orEmpty()
        if (profiles.isNotEmpty()) out += BrowserInfo("mac-firefox", "Firefox", Kind.FIREFOX, profiles)
        return out
    }

    /** Chromium stores cookies below User Data/<profile>/Network/Cookies. */
    private fun chromiumProfiles(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        if (cookieDb(root).isFile) return listOf(root)
        return root.listFiles { f -> f.isDirectory && cookieDb(f).isFile }?.toList().orEmpty()
    }

    private fun cookieDb(profile: File): File = File(profile, "Network").resolve("Cookies")

    /** 从指定浏览器导入指定平台的 Cookie；未找到返回 null */
    fun importFrom(browser: BrowserInfo, domains: List<String>): FoundCookies? =
        when (browser.kind) {
            Kind.CHROMIUM -> importChromium(browser, domains)
            Kind.FIREFOX -> importFirefox(browser, domains)
        }

    // ---------- Chromium ----------

    private fun importChromium(browser: BrowserInfo, domains: List<String>): FoundCookies? {
        val safeStorageKey = if (isMac()) macSafeStorageKey(browser.displayName) else null
        for (root in browser.profileDirs) {
            val cookiesFile = cookieDb(root)
            if (!cookiesFile.exists()) continue
            val copy = File(AppContext.cacheDir, "browser-cookies-${System.nanoTime()}.db")
            try {
                cookiesFile.copyTo(copy, overwrite = true)
                val cookie = extractChromium(copy, domains, safeStorageKey) ?: continue
                return FoundCookies(browser.displayName, cookie, cookie.split(';').size)
            } catch (e: Exception) {
                Log.w(TAG, "读取 ${browser.displayName} Cookie 失败: ${e.message}")
            } finally {
                runCatching { copy.delete() }
            }
        }
        return null
    }

    private fun extractChromium(dbFile: File, domains: List<String>, safeStorageKey: ByteArray?): String? {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            val likeClause = domains.joinToString(" OR ") { "host_key LIKE ?" }
            conn.prepareStatement(
                "SELECT host_key, name, encrypted_value FROM cookies WHERE $likeClause"
            ).use { st ->
                domains.forEachIndexed { i, d -> st.setString(i + 1, "%$d%") }
                st.executeQuery().use { rs ->
                    val pairs = mutableListOf<String>()
                    while (rs.next()) {
                        val name = rs.getString(2)
                        val value = decryptChromiumValue(rs.getBytes(3), safeStorageKey) ?: continue
                        if (name.isNotBlank()) pairs += "$name=$value"
                    }
                    if (pairs.isEmpty()) return null
                    return pairs.joinToString("; ")
                }
            }
        }
    }

    /**
     * Chromium encrypted_value：
     * - "v10" 前缀 + DPAPI 载荷 → CryptUnprotectData 解密；
     * - "v20" 前缀（应用绑定加密，Chrome 127+）→ 返回 null（无法解密，跳过后整体提示）；
     * - 无前缀（个别旧版）→ 直接按 DPAPI 尝试。
     */
    private fun decryptChromiumValue(bytes: ByteArray, safeStorageKey: ByteArray?): String? {
        if (bytes.size < 3) return null
        var offset = 0
        if (bytes[0] == 'v'.code.toByte() &&
            bytes[1] in '0'.code.toByte()..'9'.code.toByte() &&
            bytes[2] in '0'.code.toByte()..'9'.code.toByte()
        ) {
            // v10 / v20 等版本前缀
            val version = (bytes[1] - '0'.code.toByte()) * 10 + (bytes[2] - '0'.code.toByte())
            if (version >= 20) return null // 应用绑定加密，无法解密
            offset = 3
        }
        val payload = if (offset > 0) bytes.copyOfRange(offset, bytes.size) else bytes
        if (isMac()) {
            val key = safeStorageKey ?: return null
            return decryptMacChromiumValue(payload, key)
        }
        return runCatching {
            val plain = Crypt32Util.cryptUnprotectData(payload)
            String(plain, Charsets.UTF_8)
        }.getOrNull()
    }

    /** Chrome/Edge v10-v11 values on macOS use PBKDF2(Safe Storage, saltysalt) + AES-CBC. */
    private fun decryptMacChromiumValue(payload: ByteArray, safeStorageKey: ByteArray): String? = runCatching {
        val spec = PBEKeySpec(
            String(safeStorageKey, Charsets.UTF_8).toCharArray(),
            "saltysalt".toByteArray(Charsets.UTF_8),
            1003,
            128
        )
        val rawKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(rawKey, "AES"),
            IvParameterSpec(ByteArray(16) { ' '.code.toByte() })
        )
        val plain = cipher.doFinal(payload)
        val end = plain.lastOrNull()?.toInt()?.and(0xff)?.let { padding ->
            if (padding in 1..16 && plain.takeLast(padding).all { it.toInt() and 0xff == padding }) {
                plain.size - padding
            } else {
                plain.size
            }
        } ?: 0
        String(plain, 0, end, Charsets.UTF_8).trimEnd('\u0000')
    }.getOrNull()

    /** Read the browser's Safe Storage password through the user's macOS Keychain. */
    private fun macSafeStorageKey(browserName: String): ByteArray? {
        val service = when (browserName.lowercase()) {
            "edge" -> "Microsoft Edge Safe Storage"
            "brave" -> "Brave Safe Storage"
            "vivaldi" -> "Vivaldi Safe Storage"
            "opera" -> "Opera Safe Storage"
            else -> "Chrome Safe Storage"
        }
        return runCatching {
            val process = ProcessBuilder(
                "/usr/bin/security", "find-generic-password", "-s", service, "-w"
            ).redirectErrorStream(true).start()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            if (process.exitValue() != 0) return@runCatching null
            process.inputStream.bufferedReader().readText().trim()
                .takeIf { it.isNotEmpty() }
                ?.toByteArray(Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "读取 $browserName Keychain 密钥失败: ${it.message}") }.getOrNull()
    }

    private fun isMac(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("mac")

    // ---------- Firefox ----------

    private fun importFirefox(browser: BrowserInfo, domains: List<String>): FoundCookies? {
        for (profile in browser.profileDirs) {
            val dbFile = File(profile, "cookies.sqlite")
            if (!dbFile.exists()) continue
            val copy = File(AppContext.cacheDir, "ff-cookies-${System.nanoTime()}.db")
            try {
                dbFile.copyTo(copy, overwrite = true)
                val cookie = extractFirefox(copy, domains) ?: continue
                return FoundCookies(browser.displayName, cookie, cookie.split(';').size)
            } catch (e: Exception) {
                Log.w(TAG, "读取 Firefox Cookie 失败: ${e.message}")
            } finally {
                runCatching { copy.delete() }
            }
        }
        return null
    }

    private fun extractFirefox(dbFile: File, domains: List<String>): String? {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            val likeClause = domains.joinToString(" OR ") { "host LIKE ?" }
            conn.prepareStatement(
                "SELECT host, name, value FROM moz_cookies WHERE $likeClause"
            ).use { st ->
                domains.forEachIndexed { i, d -> st.setString(i + 1, "%$d%") }
                st.executeQuery().use { rs ->
                    val pairs = mutableListOf<String>()
                    while (rs.next()) {
                        val name = rs.getString(2)
                        val value = rs.getString(3)
                        if (name.isNotBlank()) pairs += "$name=$value"
                    }
                    if (pairs.isEmpty()) return null
                    return pairs.joinToString("; ")
                }
            }
        }
    }
}
