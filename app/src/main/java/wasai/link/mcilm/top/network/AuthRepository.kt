package wasai.link.mcilm.top.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import wasai.link.mcilm.top.BuildConfig
import wasai.link.mcilm.top.data.TokenManager
import wasai.link.mcilm.top.link.MciLmNative
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class RateLimitException(val userMsg: String) : Exception(userMsg)
class TokenExpiredException(msg: String) : Exception(msg)

/** 400 业务码异常：用于 claimed / bound / already 等幂等语义的分支 */
class BusinessException(val code: String, val userMsg: String) : Exception(userMsg)

class AuthRepository(tokenManager: TokenManager) {
    private val tokenManager: TokenManager = tokenManager

    private val json = Json { ignoreUnknownKeys = true }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
            else HttpLoggingInterceptor.Level.NONE
        })
        .build()

    private val api: ApiService = Retrofit.Builder()
        .baseUrl("https://link.mcilm.top")
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ApiService::class.java)

    fun getLocalToken(): String? = tokenManager.getToken()
    fun logout() = tokenManager.clearToken()

    suspend fun checkAccountStatus(): Boolean {
        val token = getLocalToken() ?: return true
        return try { api.getMe("Bearer $token"); true }
        catch (e: HttpException) { e.code() != 403 }
        catch (_: Exception) { true }
    }

    suspend fun loginAndGetPermanentToken(account: String, password: String): Result<String> {
        return try {
            val loginResp = api.login(LoginRequest(account, password))
            val meResp = api.getMe("Bearer ${loginResp.token}")
            tokenManager.saveToken(meResp.apiToken)
            Result.success(meResp.apiToken)
        } catch (e: Exception) { Result.failure(handleException(e)) }
    }

    suspend fun getRoomTime(): Result<RoomTimeResponse> {
        val token = getLocalToken() ?: return Result.failure(Exception("未登录"))
        return try { Result.success(api.getRoomTime("Bearer $token")) }
        catch (e: Exception) { Result.failure(handleException(e)) }
    }

    suspend fun checkIn(): Result<CheckInResponse> {
        val token = getLocalToken() ?: return Result.failure(Exception("未登录"))
        return try {
            Result.success(api.checkIn("Bearer $token"))
        } catch (e: HttpException) {
            if (e.code() == 400) {
                val errorBody = e.response()?.errorBody()?.string()
                if (errorBody?.contains("already") == true) Result.success(CheckInResponse(0L, 0L))
                else Result.failure(handleException(e))
            } else Result.failure(handleException(e))
        } catch (e: Exception) { Result.failure(handleException(e)) }
    }

    suspend fun bindRoom(roomId: String) {
        val token = getLocalToken() ?: return
        try {
            val resp = api.bindRoom("Bearer $token", BindRequest(roomId))
            if (!resp.isSuccessful) MciLmNative.logApp("bindRoom 失败: HTTP ${resp.code()}")
        } catch (e: Exception) { MciLmNative.logApp("bindRoom 异常: ${e.message}") }
    }

    suspend fun getSponsor(): Result<SponsorResponse> {
        val token = getLocalToken() ?: return Result.failure(Exception("未登录"))
        return try { Result.success(api.getSponsor("Bearer $token")) }
        catch (e: Exception) { Result.failure(handleException(e)) }
    }

    suspend fun getPublicRooms(): Result<PublicRoomsResponse> {
        return try { Result.success(api.getPublicRooms()) }
        catch (e: Exception) { Result.failure(handleException(e)) }
    }

    suspend fun getConnectInfo(roomId: String, ip: String): Result<ConnectResponse> {
        val token = getLocalToken() ?: return Result.failure(Exception("未登录"))
        return try { Result.success(api.getConnectInfo("Bearer $token", roomId, ip)) }
        catch (e: Exception) { Result.failure(handleException(e)) }
    }

    suspend fun getUserNodes(): Result<UserNodesResponse> {
        val token = getLocalToken() ?: return Result.failure(Exception("未登录"))
        return try { Result.success(api.getUserNodes("Bearer $token")) }
        catch (e: Exception) { Result.failure(handleException(e)) }
    }

    suspend fun getLinkmStatus(): Result<LinkmStatusResponse> {
        return try { Result.success(api.getLinkmStatus()) }
        catch (e: Exception) { Result.failure(handleException(e)) }
    }

    suspend fun rotateToken(): Result<String> {
        val token = getLocalToken() ?: return Result.failure(Exception("未登录"))
        return try {
            val resp = api.rotateToken("Bearer $token")
            tokenManager.saveToken(resp.apiToken)
            Result.success(resp.apiToken)
        } catch (e: Exception) { Result.failure(handleException(e)) }
    }

    // ==================== 公告 ====================

    suspend fun getAnnouncements(): Result<AnnouncementsResponse> {
        val token = getLocalToken() ?: return Result.failure(Exception("未登录"))
        return try { Result.success(api.getAnnouncements("Bearer $token")) }
        catch (e: Exception) { Result.failure(handleException(e)) }
    }

    suspend fun markAnnouncementRead(id: Int): Result<Unit> {
        val token = getLocalToken() ?: return Result.failure(Exception("未登录"))
        return try {
            val resp = api.markAnnouncementRead("Bearer $token", id)
            if (resp.isSuccessful) Result.success(Unit)
            else Result.failure(Exception("标记已读失败: HTTP ${resp.code()}"))
        } catch (e: Exception) { Result.failure(handleException(e)) }
    }

    // ==================== 活动 ====================

    suspend fun getActivities(): Result<ActivitiesResponse> {
        val token = getLocalToken() ?: return Result.failure(Exception("未登录"))
        return try { Result.success(api.getActivities("Bearer $token")) }
        catch (e: Exception) { Result.failure(handleException(e)) }
    }

    suspend fun claimActivity(id: Int): Result<ClaimActivityResponse> {
        val token = getLocalToken() ?: return Result.failure(Exception("未登录"))
        return try {
            Result.success(api.claimActivity("Bearer $token", id))
        } catch (e: HttpException) {
            if (e.code() == 400) {
                val err = parseErrorBody(e)
                if (err?.code == "claimed") Result.failure(BusinessException("claimed", "该活动奖励已领取"))
                else Result.failure(Exception(err?.message ?: "领取失败"))
            } else Result.failure(handleException(e))
        } catch (e: Exception) { Result.failure(handleException(e)) }
    }

    // ==================== 邀请 ====================

    suspend fun getInvite(): Result<InviteResponse> {
        val token = getLocalToken() ?: return Result.failure(Exception("未登录"))
        return try { Result.success(api.getInvite("Bearer $token")) }
        catch (e: Exception) { Result.failure(handleException(e)) }
    }

    suspend fun bindInvite(code: String): Result<BindInviteResponse> {
        val token = getLocalToken() ?: return Result.failure(Exception("未登录"))
        return try {
            Result.success(api.bindInvite("Bearer $token", BindInviteRequest(code)))
        } catch (e: HttpException) {
            if (e.code() == 400) {
                val err = parseErrorBody(e)
                if (err?.code == "bound") Result.failure(BusinessException("bound", "您已绑定过邀请码"))
                else Result.failure(Exception(err?.message ?: "邀请码绑定失败"))
            } else if (e.code() == 403) {
                Result.failure(Exception("邀请功能暂未开启"))
            } else Result.failure(handleException(e))
        } catch (e: Exception) { Result.failure(handleException(e)) }
    }

    // ==================== 反馈 ====================

    suspend fun submitFeedback(category: String, content: String, contact: String?): Result<FeedbackSubmitResponse> {
        val token = getLocalToken() ?: return Result.failure(Exception("未登录"))
        return try {
            Result.success(api.submitFeedback("Bearer $token", FeedbackSubmitRequest(category, content, contact)))
        } catch (e: Exception) { Result.failure(handleException(e)) }
    }

    suspend fun getFeedbackList(): Result<FeedbackListResponse> {
        val token = getLocalToken() ?: return Result.failure(Exception("未登录"))
        return try { Result.success(api.getFeedbackList("Bearer $token")) }
        catch (e: Exception) { Result.failure(handleException(e)) }
    }

    // ==================== 内部工具 ====================

    private fun parseErrorBody(e: HttpException): ErrorResponse? {
        return try {
            e.response()?.errorBody()?.string()?.let { json.decodeFromString<ErrorResponse>(it) }
        } catch (_: Exception) { null }
    }

    private fun handleException(e: Throwable): Exception {
        if (e is UnknownHostException) return Exception("DNS 解析异常，请检查网络连接")
        if (e is ConnectException) return Exception("无法连接服务器，请检查网络")
        if (e is SocketTimeoutException) return Exception("服务器响应超时，请稍后重试")

        if (e is HttpException) {
            val code = e.code()
            val errorBodyStr = e.response()?.errorBody()?.string()
            val errorData = try {
                errorBodyStr?.let { json.decodeFromString<ErrorResponse>(it) }
            } catch (_: Exception) { null }

            val serverMsg = errorData?.message
            val serverHint = errorData?.hint
            val errorMsg = serverMsg ?: serverHint ?: "请求失败"

            if (code == 429) return RateLimitException(errorMsg)

            return when (code) {
                401 -> {
                    tokenManager.clearToken()
                    TokenExpiredException(serverMsg ?: "登录已过期，请重新登录")
                }
                403 -> Exception("账号已被封禁：$errorMsg")
                502, 503, 504 -> Exception("服务器响应超时或正在维护，请稍后重试")
                else -> Exception(errorMsg)
            }
        }
        return Exception(e.message ?: "网络请求出错")
    }
}