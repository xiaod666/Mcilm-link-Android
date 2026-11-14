package wasai.link.mcilm.top.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @POST("/api/auth/sso/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @GET("/api/auth/me")
    suspend fun getMe(@Header("Authorization") bearerToken: String): MeResponse

    @GET("/api/user/roomtime")
    suspend fun getRoomTime(@Header("Authorization") bearerToken: String): RoomTimeResponse

    @POST("/api/user/checkin")
    suspend fun checkIn(@Header("Authorization") bearerToken: String): CheckInResponse

    @POST("/api/user/rooms/bind")
    suspend fun bindRoom(
        @Header("Authorization") bearerToken: String,
        @Body request: BindRequest
    ): Response<Unit>

    @GET("/api/user/sponsor")
    suspend fun getSponsor(@Header("Authorization") bearerToken: String): SponsorResponse

    @GET("/api/public/linkm/rooms")
    suspend fun getPublicRooms(): PublicRoomsResponse

    @GET("/api/user/connect")
    suspend fun getConnectInfo(
        @Header("Authorization") bearerToken: String,
        @Query("room_id") roomId: String,
        @Query("ip") ip: String
    ): ConnectResponse

    @GET("/api/user/nodes")
    suspend fun getUserNodes(@Header("Authorization") bearerToken: String): UserNodesResponse

    @GET("/api/public/linkm/status")
    suspend fun getLinkmStatus(): LinkmStatusResponse

    @POST("/api/user/token")
    suspend fun rotateToken(@Header("Authorization") bearerToken: String): RotateTokenResponse

    // ==================== 公告 ====================

    @GET("/api/user/announcements")
    suspend fun getAnnouncements(@Header("Authorization") bearerToken: String): AnnouncementsResponse

    @POST("/api/user/announcements/{id}/read")
    suspend fun markAnnouncementRead(
        @Header("Authorization") bearerToken: String,
        @Path("id") id: Int
    ): Response<Unit>

    // ==================== 活动 ====================

    @GET("/api/user/activities")
    suspend fun getActivities(@Header("Authorization") bearerToken: String): ActivitiesResponse

    @POST("/api/user/activities/{id}/claim")
    suspend fun claimActivity(
        @Header("Authorization") bearerToken: String,
        @Path("id") id: Int
    ): ClaimActivityResponse

    // ==================== 邀请 ====================

    @GET("/api/user/invite")
    suspend fun getInvite(@Header("Authorization") bearerToken: String): InviteResponse

    @POST("/api/user/invite/bind")
    suspend fun bindInvite(
        @Header("Authorization") bearerToken: String,
        @Body request: BindInviteRequest
    ): BindInviteResponse

    // ==================== 反馈 ====================

    @POST("/api/user/feedback")
    suspend fun submitFeedback(
        @Header("Authorization") bearerToken: String,
        @Body request: FeedbackSubmitRequest
    ): FeedbackSubmitResponse

    @GET("/api/user/feedback")
    suspend fun getFeedbackList(@Header("Authorization") bearerToken: String): FeedbackListResponse
}