package wasai.link.mcilm.top.network

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

interface UpdateApi {
    /**
     * 获取最新 Release。
     * 带 If-None-Match 时 GitHub 返回 304，不消耗 rate limit 配额。
     */
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Header("Accept") accept: String = "application/vnd.github+json",
        @Header("If-None-Match") etag: String? = null,
        @Header("Authorization") auth: String? = null
    ): Response<GitHubRelease>
}