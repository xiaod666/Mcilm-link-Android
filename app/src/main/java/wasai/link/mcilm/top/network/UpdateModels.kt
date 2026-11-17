package wasai.link.mcilm.top.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("published_at") val publishedAt: String = "",
    val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
    val name: String = "",
    val size: Long = 0L,
    @SerialName("browser_download_url") val downloadUrl: String = ""
)

/**
 * 宿主侧统一的"更新描述"。
 *
 * 双源改造：末尾新增 tagName / isForce / source 三个字段，**全部带默认值**，
 * 现有构造调用与解构不会被打断。
 *  - tagName : Release tag 原文（如 "v1.6.3"），用于缓存文件名、日志
 *  - isForce : 命中 Release body 中的 <!-- minVersionCode: N --> 且当前版本 < N
 *  - source  : "github" / "gitee"，用于 Referer / 缓存名区分 / 日志归因
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long,
    val publishedAt: String,
    val isPrerelease: Boolean,
    // ---- 双源改造新增（带默认值，不破坏现有构造） ----
    val tagName: String = "",
    val isForce: Boolean = false,
    val source: String = "github"
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data object NotAvailable : UpdateState
    data class Downloading(
        val progress: Int,
        val downloaded: Long,
        val total: Long
    ) : UpdateState
    data class Downloaded(val file: File, val info: UpdateInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}