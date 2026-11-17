package wasai.link.mcilm.top.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import wasai.link.mcilm.top.BuildConfig
import wasai.link.mcilm.top.link.MciLmNative
import wasai.link.mcilm.top.network.GitHubRelease
import wasai.link.mcilm.top.network.UpdateInfo
import java.io.IOException

/**
 * GitHub Releases 实现。复用 UpdateModels.kt 中已有的 GitHubRelease / GitHubAsset。
 * 特征：Authorization: Bearer、ETag（304 复用缓存）、api.github.com。
 */
internal class GitHubUpdateSource : UpdateSource {

    override val name = "github"

    override suspend fun fetchLatest(
        ctx: Context,
        http: OkHttpClient,
        json: Json,
        prefs: SharedPreferences,
        force: Boolean
    ): UpdateInfo? = withContext(Dispatchers.IO) {

        // 6h 节流（非 force）：直接读缓存
        if (!force) {
            val lastAt = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
            if (System.currentTimeMillis() - lastAt < BuildConfig.UPDATE_CHECK_INTERVAL_MS) {
                return@withContext parseCached(json, prefs)
            }
        }

        val url = "${BuildConfig.UPDATE_GITHUB_API_BASE.trimEnd('/')}" +
                "/repos/${BuildConfig.UPDATE_GITHUB_OWNER}/${BuildConfig.UPDATE_GITHUB_REPO}/releases/latest"

        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()

        if (BuildConfig.UPDATE_GITHUB_TOKEN.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer ${BuildConfig.UPDATE_GITHUB_TOKEN}")
        }
        if (!force) {
            prefs.getString(KEY_ETAG, null)?.let { reqBuilder.header("If-None-Match", it) }
        }

        http.newCall(reqBuilder.build()).execute().use { resp ->
            val now = System.currentTimeMillis()

            // 304：内容未变 → 复用缓存
            if (resp.code == 304) {
                prefs.edit().putLong(KEY_LAST_CHECK_AT, now).apply()
                return@withContext parseCached(json, prefs)
            }
            if (!resp.isSuccessful) throw IOException("GitHub API HTTP ${resp.code}")

            val bodyStr = resp.body?.string()
                ?: throw IOException("GitHub API 空响应")

            resp.header("ETag")?.let { prefs.edit().putString(KEY_ETAG, it).apply() }
            prefs.edit()
                .putString(KEY_CACHED_JSON, bodyStr)
                .putLong(KEY_LAST_CHECK_AT, now)
                .apply()

            return@withContext buildUpdateInfo(json, bodyStr)
        }
    }

    private fun parseCached(json: Json, prefs: SharedPreferences): UpdateInfo? {
        val s = prefs.getString(KEY_CACHED_JSON, null) ?: return null
        return buildUpdateInfo(json, s)
    }

    private fun buildUpdateInfo(json: Json, bodyStr: String): UpdateInfo? {
        val release = json.decodeFromString(GitHubRelease.serializer(), bodyStr)

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

        val assets = release.assets.map { it.name to it.size }
        val idx = UpdateSourceUtil.pickAsset(assets, BuildConfig.UPDATE_ASSET_FILTER)

        if (idx < 0) {
            MciLmNative.logApp(
                "[Update][github] 有新版本 $versionName 但无匹配资产 filter=${BuildConfig.UPDATE_ASSET_FILTER}"
            )
            return UpdateInfo(
                versionName = versionName,
                versionCode = versionCode,
                releaseNotes = UpdateSourceUtil.simplifyMarkdown(body),
                downloadUrl = "",
                apkSize = 0L,
                publishedAt = release.publishedAt,
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
            apkSize = assets[idx].second,
            publishedAt = release.publishedAt,
            isPrerelease = release.prerelease,
            tagName = tag,
            isForce = isForce,
            source = name
        )
    }

    private companion object {
        const val KEY_ETAG = "gh_etag"
        const val KEY_CACHED_JSON = "gh_cached_json"
        const val KEY_LAST_CHECK_AT = "gh_last_check_at"
    }
}