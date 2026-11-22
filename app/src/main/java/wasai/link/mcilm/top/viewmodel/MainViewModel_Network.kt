package wasai.link.mcilm.top.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import wasai.link.mcilm.top.link.MciLmNative
import wasai.link.mcilm.top.network.BannedException
import wasai.link.mcilm.top.network.RateLimitException
import wasai.link.mcilm.top.network.TokenExpiredException
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// ===== 网络与账号模块 =====

internal fun MainViewModel.registerNetworkCallback() {
    val networkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (wasOffline) {
                wasOffline = false
                networkLostJob?.cancel()
                networkLostJob = null
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("网络已恢复"))
            }
        }

        override fun onLost(network: Network) {
            wasOffline = true
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("网络连接已断开，等待自动恢复...", isLong = true))
            networkLostJob?.cancel()
            // P0-2 修复：仅做 UI 提示，不主动 stopSession()，会话销毁交给 SDK 或终止事件。
            networkLostJob = viewModelScope.launch {
                delay(5000.milliseconds)
                if (wasOffline && (_uiState.value.isHosting || _uiState.value.isJoining)) {
                    _uiEvent.tryEmit(UiEvent.ShowSnackbar("网络持续断开，请检查网络连接", isLong = true))
                }
            }
        }
    }
    networkCallback = cb
    connectivityManager.registerNetworkCallback(networkRequest, cb)
}

// ==================== 登录与账号 ====================

internal fun MainViewModel.login(
    account: String,
    password: String,
    rememberMe: Boolean,
    isSilent: Boolean = false
) {
    if (account.isBlank() || password.isBlank()) {
        _uiState.value = _uiState.value.copy(errorMsg = "账号密码不能为空")
        return
    }
    lastLoginParams = Pair(account, password)
    _uiState.value = _uiState.value.copy(errorMsg = null, isLoading = true, loadingMsg = "正在连接服务器...")
    viewModelScope.launch {
        try {
            val result = repo.loginAndGetPermanentToken(account, password)
            result.onSuccess {
                tokenManager.saveCurrentAccount(account)

                if (isSilent) {
                    tokenManager.updateAutoLoginTime(account)
                } else {
                    if (rememberMe) {
                        tokenManager.saveRememberedAccount(account, password)
                    } else {
                        tokenManager.removeRememberedAccount(account)
                    }
                }

                _uiState.value = _uiState.value.copy(authState = AuthState.Authenticated, isLoading = false)
                silentCheckInAndRefreshBalance()
                refreshSponsor()
                fetchPublicRooms()
                fetchNodeNames()
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(authState = AuthState.Unauthenticated, isLoading = false)
                when (e) {
                    is RateLimitException -> {
                        _uiEvent.tryEmit(UiEvent.ShowSnackbar("您操作得太快啦", isLong = true))
                    }
                    is TokenExpiredException -> {
                        // P1 修复：仅鉴权失效才清密码，网络错误不清
                        if (isSilent) tokenManager.invalidateAccountPassword(account)
                        _uiState.value = _uiState.value.copy(
                            accountUpdateCounter = _uiState.value.accountUpdateCounter + 1
                        )
                        _uiEvent.tryEmit(UiEvent.ShowSnackbar("登录已失效，请重新输入密码", isLong = true))
                    }
                    is BannedException -> {
                        // 封禁不清密码，解封后可继续用
                        _uiState.value = _uiState.value.copy(errorMsg = e.message ?: "账号已被封禁")
                    }
                    else -> {
                        // 网络错误：免密登录不清密码，静默失败
                        if (isSilent) {
                            _uiEvent.tryEmit(UiEvent.ShowSnackbar("网络异常，免密登录失败，请稍后重试", isLong = true))
                        } else {
                            _uiState.value = _uiState.value.copy(errorMsg = e.message ?: "登录失败")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            _uiState.value = _uiState.value.copy(isLoading = false, errorMsg = "系统错误: ${t.message}")
        }
    }
}

internal fun MainViewModel.loginWithSpecificAccount(account: String) {
    val accounts = tokenManager.getRememberedAccounts()
    val target = accounts.find { it.account == account }
    if (target == null || target.password.isBlank()) {
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("该账号密码记录已失效，请重新输入密码"))
        return
    }
    login(target.account, target.password, rememberMe = false, isSilent = true)
}

internal fun MainViewModel.switchAccount() {
    logout()
}

internal fun MainViewModel.removeRememberedAccount(account: String) {
    tokenManager.removeRememberedAccount(account)
}

// ==================== 额度与签到 ====================

internal fun MainViewModel.refreshBalance() {
    viewModelScope.launch {
        repo.getRoomTime()
            .onSuccess { _uiState.value = _uiState.value.copy(walletSeconds = it.walletSeconds) }
            .onFailure { e ->
                if (!handleAuthError(e)) {
                    MciLmNative.logApp("获取时长失败: ${e.message}")
                    _uiEvent.tryEmit(UiEvent.ShowSnackbar("获取时长失败: ${e.message}", isLong = true))
                }
            }
    }
}

internal fun MainViewModel.silentCheckInAndRefreshBalance() {
    viewModelScope.launch {
        val checkInResult = repo.checkIn()
        checkInResult.onSuccess { res ->
            if (res.claimedSeconds > 0) {
                val min = res.claimedSeconds / 60
                val hours = min / 60
                val remainMin = min % 60
                val timeStr = if (hours > 0) "${hours}小时${remainMin}分钟" else "${min}分钟"
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("今日签到成功，已获得 $timeStr 联机时长", isLong = true))
            }
        }.onFailure { err ->
            if (handleAuthError(err)) return@launch
            MciLmNative.logApp("登录后静默签到失败(不阻断): ${err.message}")
        }

        repo.getRoomTime()
            .onSuccess { _uiState.value = _uiState.value.copy(walletSeconds = it.walletSeconds) }
            .onFailure { err ->
                if (!handleAuthError(err)) {
                    MciLmNative.logApp("登录后获取时长失败: ${err.message}")
                }
            }
    }
}

internal fun MainViewModel.refreshSponsor() {
    viewModelScope.launch {
        repo.getSponsor()
            .onSuccess { s -> _uiState.value = _uiState.value.copy(roomMax = s.roomMax) }
            .onFailure { e ->
                if (!handleAuthError(e)) MciLmNative.logApp("获取权益失败: ${e.message}")
            }
    }
}

internal fun MainViewModel.checkIn() {
    if (isClickTooFast()) return
    viewModelScope.launch {
        _uiState.value = _uiState.value.copy(isLoading = true, loadingMsg = "正在签到...")
        repo.checkIn().onSuccess { res ->
            if (res.claimedSeconds > 0) {
                val min = res.claimedSeconds / 60
                _uiEvent.tryEmit(UiEvent.ShowSnackbar(if (min > 0) "签到成功！获得 $min 分钟时长" else "签到成功！"))
            } else {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("今日已签到"))
            }
            refreshBalance()
        }.onFailure { e ->
            if (!handleAuthError(e)) {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("签到失败: ${e.message}", isLong = true))
            }
        }
        _uiState.value = _uiState.value.copy(isLoading = false)
    }
}

// ==================== 房间与节点 ====================

internal fun MainViewModel.fetchPublicRooms() {
    viewModelScope.launch {
        repo.getPublicRooms()
            .onSuccess { _uiState.value = _uiState.value.copy(publicRooms = it.rooms) }
            .onFailure { e -> handleAuthError(e) }
    }
}

internal fun MainViewModel.fetchNodeNames() {
    viewModelScope.launch(Dispatchers.IO) {
        val userNodesDeferred = async { repo.getUserNodes() }
        val statusDeferred = async { repo.getLinkmStatus() }
        val userNodesRes = userNodesDeferred.await()
        val statusRes = statusDeferred.await()
        val map = mutableMapOf<String, String>()
        userNodesRes.onSuccess { userData ->
            statusRes.onSuccess { statusData ->
                val ips = userData.ips.ifEmpty { userData.nodes.map { it.ip } }
                val names = statusData.nodes.map { it.name }
                for (i in 0 until minOf(ips.size, names.size)) {
                    map[ips[i].substringBefore(":").trim()] = names[i].trim()
                }
            }
        }
        nodeNamesMap = map
    }
}

internal fun MainViewModel.resolveNodeName(server: String): String {
    if (server.isBlank()) return "自动分配"
    if (nodeNamesMap.isEmpty()) return "节点列表加载中..."
    return nodeNamesMap[server.substringBefore(":").trim()] ?: "自定义节点"
}

internal fun MainViewModel.isPortAvailable(port: Int): Boolean {
    return try { java.net.ServerSocket(port).use { true } } catch (_: Exception) { false }
}

internal fun MainViewModel.startPatrol() {
    stopPatrol()
    loadChatHistory()
    fetchNetworkStatus()
    fetchFileTasks()
    if (!_uiState.value.isHosting) checkFileSyncSupport()
    patrolJob = viewModelScope.launch(Dispatchers.IO) {
        var tick = 0
        while (isActive) {
            fetchMembers()
            fetchNetworkStatus()
            fetchFileTasks()
            if (tick % 100 == 0) {
                repo.getRoomTime().onSuccess { rt ->
                    val wasExhausted = _uiState.value.isTimeExhausted
                    _uiState.value = _uiState.value.copy(
                        walletSeconds = rt.walletSeconds,
                        isTimeExhausted = !rt.canHost
                    )
                    if (!rt.canHost && !wasExhausted) {
                        _uiEvent.tryEmit(UiEvent.ShowSnackbar("房间时长已用尽，新玩家将无法加入", isLong = true))
                    }
                }.onFailure { e ->
                    if (handleAuthError(e)) {
                        stopPatrol()
                        stopSession()
                    }
                }
            }
            tick++
            delay(3.seconds)
        }
    }
}

internal fun MainViewModel.stopPatrol() {
    patrolJob?.cancel(); patrolJob = null
    reconnectJob?.cancel(); reconnectJob = null
}

internal fun MainViewModel.fetchNetworkStatus() {
    viewModelScope.launch(Dispatchers.IO) {
        val statusJson = MciLmNative.linkQuery("status") ?: return@launch
        try {
            val o = JSONObject(statusJson)
            val role = o.optString("role", "")
            val rawMode = (if (o.optString("policy", "").isNotEmpty()) o.optString("policy") else o.optString("link")).lowercase()

            val friendlyMode = when {
                rawMode.contains("p2p") || rawMode.contains("punch") || rawMode.contains("打洞") -> "P2P 直连 (打洞)"
                rawMode.contains("relay") || rawMode.contains("中继") -> "中继模式 (Relay)"
                rawMode.contains("direct") || rawMode.contains("直连") -> "直连模式"
                rawMode.isBlank() -> if (role == "host") {
                    if (_uiState.value.members.size > 1) "已联机 (多人数据交互中)" else "本地就绪 (等待访客加入)"
                } else "协商中..."
                else -> rawMode.uppercase()
            }

            var up = 0L; var down = 0L; var pUp = 0L; var pDown = 0L
            o.optJSONObject("traffic")?.let {
                up = it.optLong("up", 0L); down = it.optLong("down", 0L)
                pUp = it.optLong("punch_up", 0L); pDown = it.optLong("punch_down", 0L)
            }

            var mode = "text"; var micOn = true; var speakerOn = true; var vol = 5
            o.optJSONObject("chat")?.let {
                mode = it.optString("mode", "text")
                micOn = it.optBoolean("mic", true); speakerOn = it.optBoolean("speaker", true); vol = it.optInt("volume", 5)
            }

            _uiState.value = _uiState.value.copy(
                networkMode = friendlyMode,
                trafficUp = up, trafficDown = down, trafficPunchUp = pUp, trafficPunchDown = pDown,
                chatMode = mode, chatMicOn = micOn, chatSpeakerOn = speakerOn, chatVolume = vol
            )
        } catch (_: Exception) {}
    }
}

internal fun MainViewModel.switchNetworkMode(isP2P: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
        MciLmNative.linkCommand(if (isP2P) "p2p" else "relay")
        delay(500.milliseconds)
        fetchNetworkStatus()
    }
}

internal fun MainViewModel.checkPublicPermission(onResult: (Boolean) -> Unit) {
    viewModelScope.launch {
        repo.getSponsor()
            .onSuccess { onResult(it.canPublicRoom) }
            .onFailure { e -> if (!handleAuthError(e)) onResult(false) }
    }
}

internal fun MainViewModel.enableDeveloperMode() {
    _uiState.value = _uiState.value.copy(isDevMode = true)
    getApplication<Application>().getSharedPreferences("mci_settings", Context.MODE_PRIVATE)
        .edit { putBoolean("is_dev_mode", true) }
}

internal fun MainViewModel.disableDeveloperMode() {
    _uiState.value = _uiState.value.copy(isDevMode = false)
    getApplication<Application>().getSharedPreferences("mci_settings", Context.MODE_PRIVATE)
        .edit { putBoolean("is_dev_mode", false) }
}

internal fun MainViewModel.updateThreshold(threshold: Int) {
    val safe = if (threshold in 20..1000) threshold else 150
    _uiState.value = _uiState.value.copy(discoveryThreshold = safe)
    getApplication<Application>().getSharedPreferences("mci_settings", Context.MODE_PRIVATE)
        .edit { putInt("discovery_threshold", safe) }
}

internal fun MainViewModel.clearPrivateLib() {
    viewModelScope.launch(Dispatchers.IO) {
        val libFile = File(getApplication<Application>().filesDir, "lib/libmcilm_link.so")
        if (libFile.exists()) {
            if (libFile.delete()) _uiEvent.tryEmit(UiEvent.ShowSnackbar("已清理私有库，下次启动将重新下载/使用内置库"))
            else _uiEvent.tryEmit(UiEvent.ShowSnackbar("清理私有库失败，请检查权限"))
        } else {
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("私有库不存在，无需清理"))
        }
    }
}

internal fun MainViewModel.rotateToken() {
    viewModelScope.launch {
        repo.rotateToken()
            .onSuccess { newToken ->
                // 【修复】新 Token 之前被直接丢弃，用户根本无法"保存好新值"
                _uiState.value = _uiState.value.copy(rotatedToken = newToken)
                MciLmNative.logApp("持久 Token 轮换成功（长度 ${newToken.length}），旧值已失效")
                _uiEvent.tryEmit(
                    UiEvent.ShowSnackbar("Token 已轮换，旧值立即失效，请立即复制保存新值", isLong = true)
                )
            }
            .onFailure { e -> if (!handleAuthError(e)) _uiEvent.tryEmit(UiEvent.ShowSnackbar("Token 轮换失败: ${e.message}", isLong = true)) }
    }
}

/** 关闭「新 Token」弹窗（用户已复制或主动忽略） */
fun MainViewModel.dismissRotatedToken() {
    if (_uiState.value.rotatedToken == null) return
    _uiState.value = _uiState.value.copy(rotatedToken = null)
}

internal fun MainViewModel.overrideToken(newToken: String) {
    if (newToken.isBlank()) {
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("Token 不能为空"))
        return
    }
    tokenManager.overrideToken(newToken.trim())
    tokenManager.saveCurrentAccount("")
    _uiEvent.tryEmit(UiEvent.ShowSnackbar("Token 已覆盖，请重启 App 生效"))
    MciLmNative.logApp("开发者强制覆盖了 Token，并清理了本地当前账号标记")
}