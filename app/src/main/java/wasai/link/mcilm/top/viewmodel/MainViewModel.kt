package wasai.link.mcilm.top.viewmodel

import android.app.Application
import android.net.ConnectivityManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import wasai.link.mcilm.top.data.AudioEngine
import wasai.link.mcilm.top.data.TokenManager
import wasai.link.mcilm.top.data.UpdateManager
import wasai.link.mcilm.top.link.MciLmNative
import wasai.link.mcilm.top.network.ActivityInfo
import wasai.link.mcilm.top.network.Announcement
import wasai.link.mcilm.top.network.AuthRepository
import wasai.link.mcilm.top.network.BannedException
import wasai.link.mcilm.top.network.FeedbackItem
import wasai.link.mcilm.top.network.InviteResponse
import wasai.link.mcilm.top.network.PublicRoom
import wasai.link.mcilm.top.network.TokenExpiredException
import wasai.link.mcilm.top.network.UpdateInfo
import wasai.link.mcilm.top.network.UpdateState
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
    val roomMax: Int = 5,
    val isTimeExhausted: Boolean = false,
    val nodePing: Int = -1,
    val announcements: List<Announcement> = emptyList(),
    val unreadAnnouncementCount: Int = 0,
    val activities: List<ActivityInfo> = emptyList(),
    val inviteInfo: InviteResponse? = null,
    val feedbackList: List<FeedbackItem> = emptyList(),
    val accountUpdateCounter: Int = 0
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
    internal val updateManager by lazy { UpdateManager(getApplication()) }

    internal val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    internal val _uiEvent = MutableSharedFlow<UiEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    // ===== 更新模块 =====
    internal val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    internal val _updateDialogVisible = MutableStateFlow(false)
    val updateDialogVisible: StateFlow<Boolean> = _updateDialogVisible.asStateFlow()

    internal var updateJob: Job? = null
    internal var downloadJob: Job? = null

    /**
     * 最近一次成功检查到的 UpdateInfo。
     * 用于从 Error / Downloading / Downloaded 状态恢复成 Available，
     * 避免用户取消下载或重试失败后丢掉"有新版本"的上下文。
     */
    internal var lastAvailableInfo: UpdateInfo? = null

    internal var lastLoginParams: Pair<String, String>? = null
    internal var lastHostParams: HostParams? = null
    internal var lastJoinParams: ClientParams? = null
    internal var patrolJob: Job? = null
    internal var reconnectJob: Job? = null
    internal var nodeNamesMap: Map<String, String> = emptyMap()

    internal val connectivityManager =
        app.getSystemService(Application.CONNECTIVITY_SERVICE) as ConnectivityManager
    internal var wasOffline = false
    internal var networkCallback: ConnectivityManager.NetworkCallback? = null
    internal var networkLostJob: Job? = null
    internal val pendingSelfMessages = ConcurrentHashMap<String, Long>()

    // 保留以兼容旧版 MainViewModel_Room.kt；新版轮询方案不再使用
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
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true, loadingMsg = "正在校验登录状态...")

                val isTokenValid = repo.checkAccountStatus()

                if (isTokenValid) {
                    _uiState.value = _uiState.value.copy(authState = AuthState.Authenticated, isLoading = false)
                    MciLmNative.logApp("自动登录成功")
                    silentCheckInAndRefreshBalance()
                    refreshSponsor()
                    fetchNodeNames()
                    fetchAnnouncements(silent = true)
                    fetchActivities()
                } else {
                    val currentAccount = tokenManager.getCurrentAccount()
                    if (!currentAccount.isNullOrBlank()) {
                        tokenManager.invalidateAccountPassword(currentAccount)
                        MciLmNative.logApp("自动登录被拦截：本地 Token 已失效，已强制作废账号 [$currentAccount] 的免密密码")
                    } else {
                        tokenManager.invalidateAllPasswords()
                        MciLmNative.logApp("自动登录被拦截：无法定位当前账号，已作废所有免密缓存")
                    }

                    repo.logout()
                    _uiState.value = _uiState.value.copy(
                        authState = AuthState.Unauthenticated,
                        isLoading = false,
                        accountUpdateCounter = _uiState.value.accountUpdateCounter + 1
                    )
                    _uiEvent.tryEmit(
                        UiEvent.ShowSnackbar("登录状态已失效，为保障安全请重新输入密码", isLong = true)
                    )
                }
            }
        }
        registerNetworkCallback()

        // 首次自动检查更新（静默，延迟 10s 等主界面就绪）
        if (!prefs.getBoolean("update_auto_disabled", false)) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(10_000L)
                checkForUpdate(silent = true)
            }
        }
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
        return when (e) {
            is TokenExpiredException -> {
                val currentAccount = tokenManager.getCurrentAccount()
                if (!currentAccount.isNullOrBlank()) {
                    tokenManager.invalidateAccountPassword(currentAccount)
                    MciLmNative.logApp("安全警告：Token 已失效（可能被重置），已强制清除账号 [$currentAccount] 的本地免密凭据")
                } else {
                    tokenManager.invalidateAllPasswords()
                    MciLmNative.logApp("安全警告：无法定位当前账号，已强制清除所有本地免密凭据")
                }

                _uiState.value = _uiState.value.copy(
                    authState = AuthState.Unauthenticated,
                    walletSeconds = 0L,
                    errorMsg = null,
                    isLoading = false,
                    accountUpdateCounter = _uiState.value.accountUpdateCounter + 1
                )
                _uiEvent.tryEmit(
                    UiEvent.ShowSnackbar("登录凭证已失效（可能账号被重置），为保障安全请重新输入密码", isLong = true)
                )
                true
            }
            is BannedException -> {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar(e.message ?: "账号已被封禁，请联系管理员", isLong = true))
                true
            }
            else -> false
        }
    }

    fun dismissError() { _uiState.value = _uiState.value.copy(errorMsg = null) }

    override fun onCleared() {
        stopPatrol()
        networkLostJob?.cancel()
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null

        updateJob?.cancel()
        updateJob = null
        downloadJob?.cancel()
        downloadJob = null

        try {
            if (AudioEngine.isRunning()) {
                MciLmNative.linkSetAudio(false)
                AudioEngine.stop()
            }
            MciLmNative.linkStop()
        } catch (_: Throwable) {}

        super.onCleared()
    }
}