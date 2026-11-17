package wasai.link.mcilm.top.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import wasai.link.mcilm.top.BuildConfig
import wasai.link.mcilm.top.link.MciLmNative
import wasai.link.mcilm.top.network.UpdateInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：GitHub Releases / Gitee Releases 双源统一入口。
 *
 * 版本判断使用 versionName 的语义版本比较，不将语义版本转换值
 * （例如 1.6.2 → 10602）与 Android versionCode（例如 16）混用。
 */
class UpdateManager(context: Context) {

    private val ctx: Context = context.applicationContext
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("mci_update", Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // 更新模块独立 OkHttp：短超时，不添加 HttpLoggingInterceptor，
    // 避免将 APK 二进制内容写入日志。
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val sources: List<UpdateSource> = buildSources()

    @Volatile
    private var inFlight: Call? = null

    private val lock = Any()

    private fun buildSources(): List<UpdateSource> {
        val mode = BuildConfig.UPDATE_SOURCE.trim().lowercase()
        val giteeReady = BuildConfig.UPDATE_GITEE_OWNER.isNotBlank() &&
                BuildConfig.UPDATE_GITEE_REPO.isNotBlank()
        val githubReady = BuildConfig.UPDATE_GITHUB_OWNER.isNotBlank() &&
                BuildConfig.UPDATE_GITHUB_REPO.isNotBlank()

        val list = mutableListOf<UpdateSource>()

        when (mode) {
            "gitee" -> {
                if (giteeReady) list += GiteeUpdateSource()
            }

            "github" -> {
                if (githubReady) list += GitHubUpdateSource()
            }

            else -> {
                // auto 或非法值：Gitee 优先，GitHub 兜底。
                if (giteeReady) list += GiteeUpdateSource()
                if (githubReady) list += GitHubUpdateSource()
            }
        }

        MciLmNative.logApp(
            "[Update] 源配置 mode=$mode " +
                    "gitee=${if (giteeReady) "on" else "off"} " +
                    "github=${if (githubReady) "on" else "off"} " +
                    "→ 启用 [${list.joinToString { it.name }}]"
        )

        return list
    }

    // ============================================================
    // 1. 检查更新
    // ============================================================

    /**
     * 检查配置的更新源。
     *
     * @return Result.success(null) 表示已是最新版本或没有比当前版本更新的发布；
     *         Result.success(info) 表示发现新版本；
     *         Result.failure(error) 表示所有可用更新源均检查失败。
     */
    suspend fun checkForUpdate(force: Boolean): Result<UpdateInfo?> =
        withContext(Dispatchers.IO) {
            if (sources.isEmpty()) {
                return@withContext Result.failure(
                    IOException("未配置任何更新源（检查 BuildConfig.UPDATE_* 与 local.properties）")
                )
            }

            var lastError: Throwable? = null
            var hasSuccessfulResponse = false

            for (src in sources) {
                try {
                    val info = src.fetchLatest(ctx, http, json, prefs, force)
                    hasSuccessfulResponse = true

                    if (info == null) {
                        MciLmNative.logApp("[Update][${src.name}] 检查成功 → 已是最新或无适用资产")
                        return@withContext Result.success(null)
                    }

                    if (!isNewerThanInstalled(info)) {
                        MciLmNative.logApp(
                            "[Update][${src.name}] 忽略非新版本 " +
                                    "remote=${releaseVersionName(info)} " +
                                    "local=${BuildConfig.VERSION_NAME}"
                        )
                        // 此源可能滞后，继续检查其他已配置源。
                        continue
                    }

                    MciLmNative.logApp(
                        "[Update][${src.name}] 检查成功 → " +
                                "发现新版本 ${info.versionName}(${info.versionCode})"
                    )

                    return@withContext Result.success(info)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    MciLmNative.logApp(
                        "[Update][${src.name}] 检查失败: " +
                                "${e.javaClass.simpleName}: ${e.message}"
                    )
                    // 当前源失败，继续尝试下一个源。
                }
            }

            if (hasSuccessfulResponse) {
                Result.success(null)
            } else {
                Result.failure(lastError ?: IOException("全部更新源均失败"))
            }
        }

    /**
     * 只通过版本名进行版本比较。
     *
     * 不比较 info.versionCode 和 BuildConfig.VERSION_CODE：
     * 某些更新源会把 1.6.2 转为 10602，这不是 Android APK 的 versionCode。
     */
    private fun isNewerThanInstalled(info: UpdateInfo): Boolean {
        val remoteVersion = parseVersion(releaseVersionName(info)) ?: return false
        val installedVersion = parseVersion(BuildConfig.VERSION_NAME) ?: return false

        return compareVersions(remoteVersion, installedVersion) > 0
    }

    private fun releaseVersionName(info: UpdateInfo): String {
        return info.versionName
            .trim()
            .ifBlank { info.tagName.trim().removePrefix("v").removePrefix("V") }
    }

    private data class ParsedVersion(
        val numbers: List<Long>,
        val prerelease: List<String>?
    )

    /**
     * 支持如下版本格式：
     * 1.6.3
     * v1.6.3
     * 1.6.3-beta
     * 1.6.3-beta.2
     * 1.6.3+build.5
     */
    private fun parseVersion(raw: String): ParsedVersion? {
        val match = VERSION_REGEX.matchEntire(raw.trim()) ?: return null

        val numbers = match.groupValues[1]
            .split(".")
            .map { it.toLongOrNull() ?: return null }

        val prereleaseValue = match.groupValues[2]
        val prerelease = if (prereleaseValue.isBlank()) {
            null
        } else {
            prereleaseValue.split(".").also { parts ->
                if (parts.any { it.isBlank() }) return null
            }
        }

        return ParsedVersion(numbers, prerelease)
    }

    /**
     * SemVer 顺序比较：
     * - 比较主版本号、次版本号、修订号；
     * - 同一正式版本中，正式版高于预发布版；
     * - 预发布标识符按 SemVer 规则比较。
     */
    private fun compareVersions(a: ParsedVersion, b: ParsedVersion): Int {
        val maxParts = maxOf(a.numbers.size, b.numbers.size)

        for (index in 0 until maxParts) {
            val left = a.numbers.getOrElse(index) { 0L }
            val right = b.numbers.getOrElse(index) { 0L }
            if (left != right) return left.compareTo(right)
        }

        val aPre = a.prerelease
        val bPre = b.prerelease

        if (aPre == null && bPre == null) return 0
        if (aPre == null) return 1
        if (bPre == null) return -1

        val maxPreParts = maxOf(aPre.size, bPre.size)

        for (index in 0 until maxPreParts) {
            if (index >= aPre.size) return -1
            if (index >= bPre.size) return 1

            val left = aPre[index]
            val right = bPre[index]
            if (left == right) continue

            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)

            val result = when {
                leftNumeric && rightNumeric -> compareNumericIdentifiers(left, right)
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }

            if (result != 0) return result
        }

        return 0
    }

    private fun compareNumericIdentifiers(a: String, b: String): Int {
        val left = a.trimStart('0').ifEmpty { "0" }
        val right = b.trimStart('0').ifEmpty { "0" }

        if (left.length != right.length) {
            return left.length.compareTo(right.length)
        }

        return left.compareTo(right)
    }

    // ============================================================
    // 2. 下载 APK
    // ============================================================

    /**
     * 下载 APK，支持 Range 续传、文件大小校验和签名校验。
     */
    suspend fun downloadApk(
        info: UpdateInfo,
        onProgress: (Long, Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        if (info.downloadUrl.isBlank()) {
            return@withContext Result.failure(
                IOException("该版本无可用下载地址（可能缺少 arm64 资产）")
            )
        }

        try {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            val tagForFile = info.tagName
                .ifBlank { "v${info.versionName}" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val apkFile = File(
                dir,
                "${info.source}_${tagForFile}_${info.versionCode}.apk"
            )

            // 缓存命中。
            if (apkFile.exists()) {
                val sizeOk = info.apkSize <= 0L || apkFile.length() == info.apkSize
                if (sizeOk && verifySignature(apkFile)) {
                    MciLmNative.logApp("[Update] 命中本地缓存 ${apkFile.name}")
                    return@withContext Result.success(apkFile)
                }

                // 文件大小或签名不匹配，删除后重新下载。
                apkFile.delete()
            }

            // Range 续传。
            val startOffset =
                if (apkFile.exists() && info.apkSize > 0L) apkFile.length() else 0L

            val reqBuilder = Request.Builder().url(info.downloadUrl)
            if (startOffset > 0L) {
                reqBuilder.header("Range", "bytes=$startOffset-")
            }

            // Gitee 附件链接偶有防盗链，带 Referer 更稳。
            if (info.source == "gitee") {
                reqBuilder.header("Referer", "https://gitee.com/")
            }

            val call = http.newCall(reqBuilder.build())
            synchronized(lock) {
                inFlight = call
            }

            try {
                call.execute().use { resp ->
                    // Range 越界：远端文件可能已替换，清空后重新下载。
                    if (resp.code == 416) {
                        apkFile.delete()
                        return@withContext downloadApk(info, onProgress)
                    }

                    if (!resp.isSuccessful && resp.code != 206) {
                        throw IOException("下载失败 HTTP ${resp.code}")
                    }

                    val body = resp.body ?: throw IOException("下载空响应")
                    val isResume = resp.code == 206
                    val resumeFrom = if (isResume) startOffset else 0L
                    val contentLen = body.contentLength()

                    val total = when {
                        info.apkSize > 0L -> info.apkSize
                        contentLen > 0L -> contentLen + resumeFrom
                        else -> 0L
                    }

                    FileOutputStream(apkFile, isResume).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            var written = resumeFrom
                            var lastReport = 0L

                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break

                                output.write(buffer, 0, read)
                                written += read

                                val now = System.currentTimeMillis()
                                if (
                                    now - lastReport >= 300L ||
                                    (total > 0L && written >= total)
                                ) {
                                    onProgress(written, total)
                                    lastReport = now
                                }
                            }

                            // 最后一次进度回调兜底。
                            onProgress(written, total)
                        }
                    }
                }
            } finally {
                synchronized(lock) {
                    inFlight = null
                }
            }

            // 已知 APK 大小时严格校验。
            if (info.apkSize > 0L && apkFile.length() != info.apkSize) {
                val actualSize = apkFile.length()
                apkFile.delete()
                throw IOException(
                    "下载大小不符：期望 ${info.apkSize}，实际 $actualSize"
                )
            }

            // 签名校验。
            if (!verifySignature(apkFile)) {
                apkFile.delete()
                throw SecurityException(
                    "安装包签名校验失败，已删除。请通过官方渠道重新下载。"
                )
            }

            MciLmNative.logApp(
                "[Update][${info.source}] 下载+校验完成 " +
                        "${apkFile.name} (${apkFile.length()}B)"
            )

            Result.success(apkFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 取消正在执行的 HTTP Call。 */
    fun cancelInFlight() {
        synchronized(lock) {
            inFlight?.cancel()
            inFlight = null
        }
    }

    // ============================================================
    // 3. APK 签名校验
    // ============================================================

    /**
     * 比较待安装 APK 与当前应用的签名。
     */
    fun verifySignature(apkFile: File): Boolean {
        return try {
            val apkSigs =
                collectApkSignatures(apkFile.absolutePath) ?: return false
            val currentSigs =
                collectApkSignatures(ctx.packageCodePath) ?: return false

            apkSigs.isNotEmpty() && apkSigs == currentSigs
        } catch (_: Throwable) {
            false
        }
    }

    private fun collectApkSignatures(apkPath: String): Set<String>? {
        val packageManager = ctx.packageManager

        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val packageInfo =
            packageManager.getPackageArchiveInfo(apkPath, flags) ?: return null

        val signatures: Array<android.content.pm.Signature> =
            if (Build.VERSION.SDK_INT >= 28) {
                val signingInfo = packageInfo.signingInfo ?: return null
                signingInfo.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures ?: return null
            }

        if (signatures.isEmpty()) return null

        val digest = MessageDigest.getInstance("SHA-256")

        return signatures.map { signature ->
            digest.reset()
            digest.digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }.toSet()
    }

    // ============================================================
    // 4. 安装权限与拉起安装器
    // ============================================================

    fun needsInstallPermission(): Boolean {
        return Build.VERSION.SDK_INT >= 26 &&
                !ctx.packageManager.canRequestPackageInstalls()
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < 26) return

        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            ctx.startActivity(intent)
        } catch (_: Exception) {
            MciLmNative.logApp(
                "[Update] 无法拉起\"安装未知应用\"设置页（部分 ROM 无此页）"
            )
        }
    }

    /**
     * @return true 表示已发出安装 Intent，不代表安装成功。
     */
    fun installApk(apkFile: File): Boolean {
        if (!apkFile.exists()) return false
        if (needsInstallPermission()) return false

        return try {
            val uri = FileProvider.getUriForFile(
                ctx,
                "${ctx.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            MciLmNative.logApp("[Update] 拉起安装器失败: ${e.message}")
            false
        }
    }

    // ============================================================
    // 5. 维护工具
    // ============================================================

    /** 清理 updates/ 下不含当前 tag 的旧 APK。 */
    fun cleanOldApks(keepTag: String? = null) {
        try {
            val dir = File(ctx.cacheDir, "updates")
            if (!dir.exists()) return

            val keepPrefix = keepTag
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")

            dir.listFiles()?.forEach { file ->
                val keep = keepPrefix != null && file.name.contains(keepPrefix)
                if (!keep) file.delete()
            }
        } catch (_: Throwable) {
            // 清理失败不影响应用运行。
        }
    }

    /** 供 UI / 日志展示当前启用的更新源。 */
    fun currentSourceNames(): String {
        return sources.joinToString("+") { it.name }.ifBlank { "未配置" }
    }

    private companion object {
        val VERSION_REGEX = Regex(
            """^v?(\d+(?:\.\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$"""
        )
    }
}