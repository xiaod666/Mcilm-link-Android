@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package wasai.link.mcilm.top.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ==================== 基础认证 ====================
@Serializable
data class LoginRequest(val account: String, val password: String)

@Serializable
data class LoginResponse(val token: String)

@Serializable
data class MeResponse(@SerialName("api_token") val apiToken: String)

// ==================== 房间/计费 ====================
@Serializable
data class RoomTimeResponse(
    @SerialName("wallet_seconds") val walletSeconds: Long = 0L,
    @SerialName("claim_seconds") val claimSeconds: Long = 0L,
    @SerialName("checked_in_today") val checkedInToday: Boolean = false,
    @SerialName("can_host") val canHost: Boolean = false
)

@Serializable
data class CheckInResponse(
    @SerialName("claimed_seconds") val claimedSeconds: Long,
    @SerialName("wallet_seconds") val walletSeconds: Long
)

@Serializable
data class BindRequest(@SerialName("room_id") val roomId: String)

@Serializable
data class ErrorResponse(
    val message: String? = null,
    val hint: String? = null,
    val code: String? = null
)

@Serializable
data class SponsorResponse(
    @SerialName("is_sponsor") val isSponsor: Boolean = false,
    @SerialName("level") val level: Int = 0,
    @SerialName("room_max") val roomMax: Int = 5,
    @SerialName("can_public_room") val canPublicRoom: Boolean = false
)

// ==================== 公开房间/节点 ====================
@Serializable
data class PublicRoomsResponse(
    val rooms: List<PublicRoom> = emptyList()
)

@Serializable
data class PublicRoom(
    @SerialName("room_id") val roomId: String = "",
    @SerialName("room_name") val roomName: String = "未知房间",
    @SerialName("node") val node: String? = null,
    @SerialName("players") val players: Int = 0,
    @SerialName("max_players") val maxPlayers: Int = 0
)

@Serializable
data class UserNodesResponse(
    val ips: List<String> = emptyList(),
    val nodes: List<UserNode> = emptyList()
)

@Serializable
data class UserNode(
    @SerialName("ip") val ip: String = ""
)

@Serializable
data class LinkmStatusResponse(
    val nodes: List<LinkmNode> = emptyList()
)

@Serializable
data class LinkmNode(
    val name: String = ""
)

@Serializable
data class RotateTokenResponse(
    @SerialName("api_token") val apiToken: String,
    @SerialName("token_created_at") val tokenCreatedAt: String = "",
    val hint: String = ""
)

// ==================== 平台能力：公告 / 活动 / 邀请 / 反馈 ====================

@Serializable
data class AnnouncementsResponse(val list: List<Announcement> = emptyList())

@Serializable
data class Announcement(
    val id: Int = 0,
    val title: String = "",
    val content: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val read: Boolean = false
)

@Serializable
data class ActivitiesResponse(val list: List<ActivityInfo> = emptyList())

@Serializable
data class ActivityInfo(
    val id: Int = 0,
    val title: String = "",
    val content: String = "",
    @SerialName("reward_sec") val rewardSec: Long = 0L,
    @SerialName("start_at") val startAt: String = "",
    @SerialName("end_at") val endAt: String = "",
    val claimed: Boolean = false
)

@Serializable
data class ClaimActivityResponse(
    val ok: Boolean = false,
    @SerialName("claimed_seconds") val claimedSeconds: Long = 0L,
    @SerialName("wallet_seconds") val walletSeconds: Long = 0L
)

@Serializable
data class InviteResponse(
    val code: String = "",
    val link: String = "",
    @SerialName("invited_count") val invitedCount: Int = 0,
    @SerialName("earned_sec") val earnedSec: Long = 0L,
    @SerialName("bound_code") val boundCode: String = "",
    val settings: InviteSettings = InviteSettings()
)

@Serializable
data class InviteSettings(
    val enabled: Boolean = false,
    @SerialName("inviter_sec") val inviterSec: Long = 0L,
    @SerialName("invitee_sec") val inviteeSec: Long = 0L
)

@Serializable
data class BindInviteRequest(val code: String)

@Serializable
data class BindInviteResponse(
    val ok: Boolean = false,
    @SerialName("inviter_gain") val inviterGain: Long = 0L,
    @SerialName("invitee_gain") val inviteeGain: Long = 0L
)

@Serializable
data class FeedbackSubmitRequest(
    val category: String,
    val content: String,
    val contact: String? = null
)

@Serializable
data class FeedbackSubmitResponse(val ok: Boolean = false, val id: Int = 0)

@Serializable
data class FeedbackListResponse(val list: List<FeedbackItem> = emptyList())

@Serializable
data class FeedbackItem(
    val id: Int = 0,
    val category: String = "",
    val content: String = "",
    val contact: String? = null,
    val status: Int = 0,
    val reply: String? = null,
    @SerialName("handled_at") val handledAt: String? = null,
    @SerialName("created_at") val createdAt: String = ""
)