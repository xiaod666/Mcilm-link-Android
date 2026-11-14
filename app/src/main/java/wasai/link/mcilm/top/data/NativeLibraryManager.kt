package wasai.link.mcilm.top.data

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URI
import java.util.concurrent.TimeUnit

class NativeLibraryManager(private val context: Context) {
    private val tag = "NativeLibManager"

    // true: 模拟器 / 非 arm64 环境强制使用 APK 内置库（x86 翻译层跑 ARM64 极易 SIGILL）
    private val blockUpdateOnEmulator = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val prefs = context.getSharedPreferences("mci_safety_prefs", Context.MODE_PRIVATE)

    data class LoadResult(
        val path: String?,
        val warningMsg: String?,
        val fatalError: String?
    )

    private fun isEmulatorOrUnsupportedArch(): Boolean {
        @Suppress("SpellCheckingInspection")
        val supportedAbis = Build.SUPPORTED_ABIS
        if (!supportedAbis.contains("arm64-v8a")) return true
        val fingerprint = Build.FINGERPRINT
        val model = Build.MODEL
        val hardware = Build.HARDWARE
        val isHoudini = File("/system/lib/libhoudini.so").exists() ||
                File("/system/lib64/libhoudini.so").exists()
        return (fingerprint.startsWith("generic") ||
                fingerprint.startsWith("unknown") ||
                model.contains("google_sdk", true) ||
                model.contains("Emulator", true) ||
                hardware.contains("goldfish", true) ||
                hardware.contains("ranchu", true) ||
                hardware.contains("mumu", true) ||
                isHoudini)
    }

    private fun compareVersion(v1: String, v2: String): Int {
        val n1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val n2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(n1.size, n2.size)) {
            val a = n1.getOrElse(i) { 0 }
            val b = n2.getOrElse(i) { 0 }
            if (a > b) return 1
            if (a < b) return -1
        }
        return 0
    }

    private fun verifyElf(file: File): Boolean {
        // 修复 P1-7：去掉 10MB 硬下限（一旦官方裁剪产物就会全体误杀），
        // 改为 ≥ 100KB 防 HTML 错误页；上限 50MB 防劫持。
        if (!file.exists() || file.length() < 100L * 1024) {
            Log.e(tag, "文件大小异常（过小，疑似错误页）: ${file.length()} bytes")
            return false
        }
        if (file.length() > 50L * 1024 * 1024) {
            Log.e(tag, "文件大小异常（过大，疑似劫持）: ${file.length()} bytes")
            return false
        }
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(64)
                raf.readFully(header)
                if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) return false
                if (header[4] != 2.toByte()) return false // 必须 ELF64
                val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
                if (machine != 0xB7) return false // 必须 AArch64
                val eType = (header[16].toInt() and 0xFF) or ((header[17].toInt() and 0xFF) shl 8)
                if (eType != 3) {
                    Log.e(tag, "拦截：文件不是动态链接库 (e_type=$eType)")
                    return false
                }
                true
            }
        } catch (e: Exception) {
            Log.e(tag, "ELF verify error", e)
            false
        }
    }

    // 修复 P1-6：只信任 link.mcilm.top 及其子域
    private fun isTrustedDownloadUrl(url: String): Boolean {
        return try {
            val uri = URI(url)
            uri.scheme == "https" &&
                    (uri.host == "link.mcilm.top" || uri.host.endsWith(".mcilm.top"))
        } catch (_: Exception) { false }
    }

    /**
     * 修复 P0-4：本函数不再写 is_attempting。
     * is_attempting 必须由 MciLmNative.initialize 在 initLibrary 前一刻设置，
     * 否则「下载 / 网络请求期间进程被杀」会被错误计为「内核崩溃」，
     * 连续 3 次后强制回退内置库，用户完全无感但版本倒退。
     *
     * 返回值 path：本次要尝试加载的私有库路径（null = 走 APK 内置库）。
     */
    suspend fun prepareNativeLibrary(): LoadResult = withContext(Dispatchers.IO) {
        val privateFile = File(context.filesDir, "lib/libmcilm_link.so")

        val isAttempting = prefs.getBoolean("is_attempting", false)
        var failCount = prefs.getInt("load_fail_count", 0)
        var warningMsg: String? = null

        if (isAttempting) {
            failCount++
            Log.e(tag, "Cold boot crash detected! fail_count: $failCount")
        } else {
            failCount = 0
        }

        if (failCount >= 3) {
            Log.e(tag, "Crash circuit breaker triggered! Force revert to builtin.")
            if (privateFile.exists()) privateFile.delete()
            failCount = 0
            warningMsg = "检测到内核连续启动异常，已自动为您回退到安全版本。"
        }

        // 只写 load_fail_count；is_attempting 由 MciLmNative 在加载前设置
        prefs.edit { putInt("load_fail_count", failCount) }

        val localVersion = prefs.getString("last_kernel_version", "0.0.0") ?: "0.0.0"
        val currentPath = if (privateFile.exists()) privateFile.absolutePath else null

        if (blockUpdateOnEmulator && isEmulatorOrUnsupportedArch()) {
            Log.w(tag, "⚠️ 拦截开关已开启，检测到模拟器环境，跳过私有库下载，强制使用内置库。")
            return@withContext LoadResult(null, null, null)
        }

        try {
            val request = Request.Builder()
                .url("https://link.mcilm.top/api/public/releases/download?platform=android&arch=arm64&build_type=lib")
                .build()

            var onlineVersion = ""
            var downloadUrl = ""

            // 修复 P1-4：Response.use 自动 close，避免连接 / 流泄漏
            val apiOk = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    warningMsg = (warningMsg ?: "") + "\n检查更新失败(网络异常)，使用当前版本。"
                    return@use false
                }
                val json = JSONObject(response.body?.string() ?: "")
                onlineVersion = json.optString("version", "")
                val rawUrl = json.optJSONObject("artifact")?.optString("url", "") ?: ""
                downloadUrl = rawUrl.substringBefore("#")
                true
            }
            if (!apiOk) return@withContext LoadResult(currentPath, warningMsg, null)

            if (downloadUrl.isNotEmpty() && compareVersion(onlineVersion, localVersion) > 0) {
                if (!isTrustedDownloadUrl(downloadUrl)) {
                    Log.e(tag, "下载 URL 不在白名单: $downloadUrl")
                    warningMsg = (warningMsg ?: "") + "\n下载源校验失败，已保护退出。"
                    return@withContext LoadResult(currentPath, warningMsg, null)
                }

                Log.d(tag, "Found new version: $onlineVersion, downloading...")
                val tmpFile = File(context.filesDir, "lib/libmcilm_link.so.tmp")
                tmpFile.parentFile?.mkdirs()
                if (tmpFile.exists()) tmpFile.delete()

                val dlRequest = Request.Builder().url(downloadUrl).build()
                val downloadOk = client.newCall(dlRequest).execute().use { dl ->
                    if (!dl.isSuccessful) {
                        Log.e(tag, "下载失败: HTTP ${dl.code}")
                        false
                    } else {
                        dl.body?.byteStream()?.use { input ->
                            FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
                        }
                        true
                    }
                }
                if (!downloadOk) {
                    if (tmpFile.exists()) tmpFile.delete()
                    warningMsg = (warningMsg ?: "") + "\n内核下载失败，使用当前版本。"
                    return@withContext LoadResult(currentPath, warningMsg, null)
                }

                if (tmpFile.exists() && verifyElf(tmpFile)) {
                    if (privateFile.exists()) privateFile.delete()
                    if (tmpFile.renameTo(privateFile)) {
                        privateFile.setReadable(true, true)
                        privateFile.setExecutable(true, true)
                        if (Build.VERSION.SDK_INT >= 34) privateFile.setReadOnly() // Safer DCL
                        Log.d(tag, "✅ 下载成功，校验通过。下次冷启动生效。")
                        prefs.edit { putString("last_kernel_version", onlineVersion) }
                    } else {
                        Log.e(tag, "renameTo 失败")
                        warningMsg = (warningMsg ?: "") + "\n内核替换失败，使用当前版本。"
                    }
                } else {
                    Log.e(tag, "❌ 文件校验失败，丢弃。")
                    if (tmpFile.exists()) tmpFile.delete()
                    warningMsg = (warningMsg ?: "") + "\n最新内核下载校验失败，已保护退出。"
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Update check failed, ignoring.", e)
            warningMsg = (warningMsg ?: "") + "\n网络不稳定，更新检查被跳过。"
        }

        return@withContext LoadResult(currentPath, warningMsg, null)
    }

    fun markHealthy() {
        prefs.edit {
            putBoolean("is_attempting", false)
            putInt("load_fail_count", 0)
        }
    }

    /** 修复 P0-4 配套：由 MciLmNative 在 initLibrary 前一刻调用 */
    fun markAttempting() {
        prefs.edit { putBoolean("is_attempting", true) }
    }
}