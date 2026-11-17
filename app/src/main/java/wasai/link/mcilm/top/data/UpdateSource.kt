package wasai.link.mcilm.top.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import wasai.link.mcilm.top.network.UpdateInfo

/**
 * 更新源抽象。UpdateManager 按 BuildConfig.UPDATE_SOURCE 决定启用哪些源。
 *
 * 每个实现负责：HTTP 请求 + JSON 解析 + 版本比较 + 资产选择。
 * 下载/签名校验/安装由 UpdateManager 统一处理（各源差异只在 downloadUrl 与 Referer）。
 */
internal interface UpdateSource {

    /** "github" / "gitee" */
    val name: String

    /**
     * @return null = 已是最新；非 null = 有新版本（含"有更新但无 arm64 资产"的降级信息）
     * @throws Exception 网络/解析失败 —— UpdateManager 会尝试下一个源
     */
    suspend fun fetchLatest(
        ctx: Context,
        http: OkHttpClient,
        json: Json,
        prefs: SharedPreferences,
        force: Boolean
    ): UpdateInfo?
}

/**
 * 各源共用工具方法。全静态，无状态。
 */
internal object UpdateSourceUtil {

    /** 语义版本 → 整数，用于比较。例：1.6.3 → 10603 */
    fun versionNameToCode(versionName: String): Int {
        val parts = versionName.trim()
            .removePrefix("v").removePrefix("V")
            .split(".").mapNotNull { it.toIntOrNull() }
        return when {
            parts.isEmpty() -> 0
            parts.size == 1 -> parts[0] * 10_000
            parts.size == 2 -> parts[0] * 10_000 + parts[1] * 100
            else -> parts[0] * 10_000 + parts[1] * 100 + parts[2]
        }
    }

    fun compareVersionNames(a: String, b: String): Int {
        fun parse(s: String) = s.trim().removePrefix("v").removePrefix("V")
            .split(".").map { it.toIntOrNull() ?: 0 }
        val pa = parse(a); val pb = parse(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** Release body 里的元数据注释 <!-- versionCode: 16 --> */
    fun parseBodyVersionCode(body: String): Int? =
        Regex("<!--\\s*versionCode:\\s*(\\d+)\\s*-->")
            .find(body)?.groupValues?.get(1)?.toIntOrNull()

    /** Release body 里的强制更新闸门 <!-- minVersionCode: 15 --> */
    fun parseBodyMinVersionCode(body: String): Int? =
        Regex("<!--\\s*minVersionCode:\\s*(\\d+)\\s*-->")
            .find(body)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Release body：剥 HTML 注释 → 简易 Markdown → 纯文本。
     * 现有 UpdateDialog 走纯文本渲染，不引 Markdown 库。
     */
    fun simplifyMarkdown(md: String): String {
        if (md.isBlank()) return "暂无更新说明"
        return md
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            .replace(Regex("^#+\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\[(.+?)]\\(.+?\\)"), "$1")
            .replace(Regex("^[-*]\\s+", RegexOption.MULTILINE), "• ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * 从 release 资产中选择匹配的 .apk。
     * @param assets      (文件名, 字节数)；size 未知时 = 0L
     * @param filter      BuildConfig.UPDATE_ASSET_FILTER（如 "arm64"）
     * @return            命中的下标；-1 = 无适配
     */
    fun pickAsset(assets: List<Pair<String, Long>>, filter: String): Int {
        if (assets.isEmpty()) return -1
        val apkIdx = assets.indices.filter {
            assets[it].first.endsWith(".apk", ignoreCase = true)
        }
        if (apkIdx.isEmpty()) return -1
        val f = filter.trim()
        if (f.isBlank()) return apkIdx.first()
        return apkIdx.firstOrNull {
            assets[it].first.contains(f, ignoreCase = true)
        } ?: -1
    }
}