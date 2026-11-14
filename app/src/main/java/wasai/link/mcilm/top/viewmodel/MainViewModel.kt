package wasai.link.mcilm.top.viewmodel

import android.app.Application
import android.net.ConnectivityManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import wasai.link.mcilm.top.data.TokenManager
import wasai.link.mcilm.top.link.MciLmNative
import wasai.link.mcilm.top.network.ActivityInfo
import wasai.link.mcilm.top.network.Announcement
import wasai.link.mcilm.top.network.AuthRepository
import wasai.link.mcilm.top.network.FeedbackItem
import wasai.link.mcilm.top.network.InviteResponse
import wasai.link.mcilm.top.network.PublicRoom
import wasai.link.mcilm.top.network.TokenExpiredException
import java.util.concurrent.ConcurrentHashMap

sealed interface AuthState {
    object Unauthenticated : AuthState
    object Authenticated : AuthState
}

sealed interface UiEvent {
    data class ShowSnackbar(val message: String, val isLong: Boolean = false) : UiEvent
}

data class MemberInfo(val peerId: String, val role: String, val online: Boolean)
data class ChatMessageInfo(val from: String, val text: String, val isSelf: Boolean, val time: String)

data class FileTaskInfo(
    val taskId: String,
    val name: String,
    val direction: String,
    val status: String,
    val bytes: Long,
    val totalBytes: Long,
    val savePath: String? = null
)

data class PendingFileRequest(val taskId: String, val name: String, val from: String, val bytes: Long)
enum class ServerMode { DEFAULT, DIRECT }

data class UiState(
    val authState: AuthState = AuthState.Unauthenticated,
    val walletSeconds: Long = 0L,
    val errorMsg: String? = null,
    val isHosting: Boolean = false,
    val roomId: String? = null,
    val roomPassword: String? = null,
    val isJoining: Boolean = false,
    val isLoading: Boolean = false,
    val loadingMsg: String = "",
    val publicRooms: List<PublicRoom> = emptyList(),
    val members: List<MemberInfo> = emptyList(),
    val serverEndpoint: String? = null,
    val localPort: String? = null,
    val chatHistory: List<ChatMessageInfo> = emptyList(),
    val networkMode: String = "获取中...",
    val serverMode: ServerMode = ServerMode.DEFAULT,
    val customServer: String = "",
    val isDevMode: Boolean = false,
    val discoveryThreshold: Int = 150,
    val trafficUp: Long = 0L,
    val trafficDown: Long = 0L,
    val trafficPunchUp: Long = 0L,
    val trafficPunchDown: Long = 0L,
    val fileTasks: List<FileTaskInfo> = emptyList(),
    val pendingFileRequest: PendingFileRequest? = null,
    val isFileSyncSupported: Boolean = true,
    val chatMode: String = "text",
    val isVoiceActive: Boolean = false,
    val chatMicOn: Boolean = true,
    val chatSpeakerOn: Boolean = true,
    val chatVolume: Int = 5,
    // ========== 开房链路 ==========
    val roomMax: Int = 5,
    val isTimeExhausted: Boolean = false,
    // ========== 平台能力 ==========
    val announcements: List<Announcement> = emptyList(),
    val unreadAnnouncementCount: Int = 0,
    val activities: List<ActivityInfo> = emptyList(),
    val inviteInfo: InviteResponse? = null,
    val feedbackList: List<FeedbackItem> = emptyList(),
)

data class HostParams(
    val roomMeta: String, val features: String, val ports: String, val pass: String,
    val isPublic: Boolean, val server: String, val mode: ServerMode,
    val minecraftPort: String = "", val sni: String = "", val plain: Boolean = false
)

data class ClientParams(
    val roomId: String, val ports: String, val pass: String, val server: String, val mode: ServerMode,
    val sni: String = "", val plain: Boolean = false, val forceRelay: Boolean = false, val preferPeers: Boolean = false
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    internal val tokenManager = TokenManager(app)
    internal val repo = AuthRepository(tokenManager)

    internal val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    internal val _uiEvent = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    internal var lastLoginParams: Pair<String, String>? = null
    internal var lastHostParams: HostParams? = null
    internal var lastJoinParams: ClientParams? = null
    internal var patrolJob: Job? = null
    internal var reconnectJob: Job? = null
    internal var nodeNamesMap: Map<String, String> = emptyMap()

    internal val connectivityManager = app.getSystemService(Application.CONNECTIVITY_SERVICE) as ConnectivityManager
    internal var wasOffline = false
    internal var networkCallback: ConnectivityManager.NetworkCallback? = null

    // 【修复】声明断网缓冲 Job
    internal var networkLostJob: Job? = null

    internal val pendingSelfMessages = ConcurrentHashMap<String, Long>()

    internal var reconnectDeferred: CompletableDeferred<Boolean>? = null
    internal val pendingSafExports = ConcurrentHashMap<String, Uri>()
    internal val taskNamesCache = ConcurrentHashMap<String, String>()

    internal var lastActionTime = 0L

    init {
        MciLmNative.logApp("App 启动，检查本地 Token")
        val prefs = app.getSharedPreferences("mci_settings", Application.MODE_PRIVATE)
        _uiState.value = _uiState.value.copy(
            isDevMode = prefs.getBoolean("is_dev_mode", false),
            discoveryThreshold = prefs.getInt("discovery_threshold", 150)
        )

        if (repo.getLocalToken() != null) {
            _uiState.value = _uiState.value.copy(authState = AuthState.Authenticated)
            MciLmNative.logApp("自动登录成功")
            silentCheckInAndRefreshBalance()
            refreshSponsor()
            fetchNodeNames()
            fetchAnnouncements(silent = true)
            fetchActivities()
        }
        registerNetworkCallback()
    }

    fun onLibraryReady() {
        MciLmNative.linkSetLog(_uiState.value.isDevMode)
        MciLmNative.linkSetEvent(true)
        observeEvents()
        if (_uiState.value.authState == AuthState.Authenticated) fetchPublicRooms()
    }

    internal fun isClickTooFast(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastActionTime < 500L) return true
        lastActionTime = now
        return false
    }

    internal fun handleAuthError(e: Throwable): Boolean {
        if (e is TokenExpiredException) {
            _uiState.value = _uiState.value.copy(
                authState = AuthState.Unauthenticated,
                walletSeconds = 0L,
                errorMsg = null,
                isLoading = false
            )
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("登录已过期，请重新登录", isLong = true))
            return true
        }
        return false
    }

    fun dismissError() { _uiState.value = _uiState.value.copy(errorMsg = null) }

    override fun onCleared() {
        stopPatrol()

        // 【修复】清理断网缓冲
        networkLostJob?.cancel()

        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
    }
}