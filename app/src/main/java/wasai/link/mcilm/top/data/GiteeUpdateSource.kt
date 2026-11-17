package wasai.link.mcilm.top.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import wasai.link.mcilm.top.BuildConfig
import wasai.link.mcilm.top.link.MciLmNative
import wasai.link.mcilm.top.network.GitHubRelease
import wasai.link.mcilm.top.network.UpdateInfo
import java.io.IOException

/**
 * Gitee Releases 实现。复用 GitHubRelease / GitHubAsset（字段完全兼容）。
 *
 * Gitee v5 API 与 GitHub 的差异：
 *  1. base = https://gitee.com/api/v5/，token 走 query `access_token`（不是 Authorization Header）
 *  2. 公开仓库无需 token
 *  3. assets[].size 可能缺失 → 传 0L，下载时用 Content-Length 兜底
 *  4. 仓库不存在 or 尚无 release → HTTP 404（这里**抛 IOException**，让 auto 模式降级到 GitHub）
 *  5. 不保证返回 ETag → 主要靠 6h 节流；如返回则兼容
 *  6. 发布日期字段是 created_at，不是 published_at（复用 GitHubRelease 时 publishedAt 会拿到 ""）
 */
internal class GiteeUpdateSource : UpdateSource {

    override val name = "gitee"

    override suspend fun fetchLatest(
        ctx: Context,
        http: OkHttpClient,
        json: Json,
        prefs: SharedPreferences,
        force: Boolean
    ): UpdateInfo? = withContext(Dispatchers.IO) {

        if (!force) {
            val lastAt = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
            if (System.currentTimeMillis() - lastAt < BuildConfig.UPDATE_CHECK_INTERVAL_MS) {
                return@withContext parseCached(json, prefs)
            }
        }

        val baseUrl = "${BuildConfig.UPDATE_GITEE_API_BASE.trimEnd('/')}" +
                "/repos/${BuildConfig.UPDATE_GITEE_OWNER}/${BuildConfig.UPDATE_GITEE_REPO}/releases/latest"

        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
        if (BuildConfig.UPDATE_GITEE_TOKEN.isNotBlank()) {
            urlBuilder.addQueryParameter("access_token", BuildConfig.UPDATE_GITEE_TOKEN)
        }

        val reqBuilder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .get()

        if (!force) {
            prefs.getString(KEY_ETAG, null)?.let { reqBuilder.header("If-None-Match", it) }
        }

        http.newCall(reqBuilder.build()).execute().use { resp ->
            val now = System.currentTimeMillis()

            if (resp.code == 304) {
                prefs.edit().putLong(KEY_LAST_CHECK_AT, now).apply()
                return@withContext parseCached(json, prefs)
            }
            // Gitee 仓库不存在或尚无 release → 404。
            // 抛异常让 auto 模式降级 GitHub；gitee 模式（强制）下则整体失败，提示用户。
            if (resp.code == 404) {
                throw IOException("Gitee API 404（仓库不存在或暂无 release）")
            }
            if (!resp.isSuccessful) throw IOException("Gitee API HTTP ${resp.code}")

            val bodyStr = resp.body?.string()
                ?: throw IOException("Gitee API 空响应")

            resp.header("ETag")?.let { prefs.edit().putString(KEY_ETAG, it).apply() }
            prefs.edit()
                .putString(KEY_CACHED_JSON, bodyStr)
                .putLong(KEY_LAST_CHECK_AT, now)
                .apply()

            return@withContext buildUpdateInfo(json, bodyStr)
        }
    }

    private fun parseCached(json: Json, prefs: SharedPreferences): UpdateInfo? {
        val s = prefs.getString(KEY_CACHED_JSON, null).orEmpty()
        if (s.isBlank()) return null
        return buildUpdateInfo(json, s)
    }

    private fun buildUpdateInfo(json: Json, bodyStr: String): UpdateInfo? {
        val release = try {
            json.decodeFromString(GitHubRelease.serializer(), bodyStr)
        } catch (_: Exception) {
            // Gitee 字段偶有出入，宽松失败
            return null
        }

        if (release.prerelease && !BuildConfig.UPDATE_ALLOW_PRERELEASE) return null

        val tag = release.tagName.trim()
        if (tag.isBlank()) return null
        val versionName = tag.removePrefix("v").removePrefix("V")
        val body = release.body

        val versionCode = UpdateSourceUtil.parseBodyVersionCode(body)
            ?: UpdateSourceUtil.versionNameToCode(versionName)

        val isNewer = versionCode > BuildConfig.VERSION_CODE ||
                (versionCode == BuildConfig.VERSION_CODE &&
                        UpdateSourceUtil.compareVersionNames(versionName, BuildConfig.VERSION_NAME) > 0)
        if (!isNewer) return null

        val minVersionCode = UpdateSourceUtil.parseBodyMinVersionCode(body)
        val isForce = minVersionCode != null && BuildConfig.VERSION_CODE < minVersionCode

        // Gitee assets 的 size 可能缺失 → 归一成 0L
        val assets = release.assets.map { it.name to it.size }
        val idx = UpdateSourceUtil.pickAsset(assets, BuildConfig.UPDATE_ASSET_FILTER)

        if (idx < 0) {
            MciLmNative.logApp(
                "[Update][gitee] 有新版本 $versionName 但无匹配资产 filter=${BuildConfig.UPDATE_ASSET_FILTER}"
            )
            return UpdateInfo(
                versionName = versionName,
                versionCode = versionCode,
                releaseNotes = UpdateSourceUtil.simplifyMarkdown(body),
                downloadUrl = "",
                apkSize = 0L,
                publishedAt = release.publishedAt,   // Gitee 通常为空串
                isPrerelease = release.prerelease,
                tagName = tag,
                isForce = isForce,
                source = name
            )
        }

        return UpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            releaseNotes = UpdateSourceUtil.simplifyMarkdown(body),
            downloadUrl = release.assets[idx].downloadUrl,
            apkSize = assets[idx].second,            // 可能为 0，下载时用 Content-Length 兜底
            publishedAt = release.publishedAt,
            isPrerelease = release.prerelease,
            tagName = tag,
            isForce = isForce,
            source = name
        )
    }

    private companion object {
        const val KEY_ETAG = "ge_etag"
        const val KEY_CACHED_JSON = "ge_cached_json"
        const val KEY_LAST_CHECK_AT = "ge_last_check_at"
    }
}