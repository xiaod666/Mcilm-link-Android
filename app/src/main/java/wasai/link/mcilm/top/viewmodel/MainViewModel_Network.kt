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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import wasai.link.mcilm.top.link.MciLmNative
import wasai.link.mcilm.top.network.RateLimitException
import java.io.File
import kotlin.time.Duration.Companion.seconds

// ===== 网络与账号模块 =====

internal fun MainViewModel.registerNetworkCallback() {
    val networkRequest = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (wasOffline) {
                wasOffline = false
                networkLostJob?.cancel()
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("网络已恢复"))
            }
        }
        override fun onLost(network: Network) {
            wasOffline = true
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("网络连接已断开，5 秒缓冲期...", isLong = true))
            networkLostJob?.cancel()
            networkLostJob = viewModelScope.launch {
                delay(5000L)
                if (wasOffline && (_uiState.value.isHosting || _uiState.value.isJoining)) {
                    _uiEvent.tryEmit(UiEvent.ShowSnackbar("网络持续断开，已结束联机", isLong = true))
                    stopSession()
                }
            }
        }
    }
    networkCallback = cb
    connectivityManager.registerNetworkCallback(networkRequest, cb)
}

// ==================== 登录与账号 ====================

internal fun MainViewModel.login(account: String, password: String, rememberMe: Boolean, isSilent: Boolean = false) {
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
                if (isSilent) {
                    // 静默登录成功后，只更新免密登录时间，不重置 10 天有效期
                    tokenManager.updateAutoLoginTime(account)
                } else {
                    // 手动登录
                    if (rememberMe) {
                        // 重新记录，重置 3 天免密期和 10 天总有效期
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
                if (e is RateLimitException) _uiEvent.tryEmit(UiEvent.ShowSnackbar("您操作得太快啦", isLong = true))
                else _uiState.value = _uiState.value.copy(errorMsg = e.message ?: "登录失败")
            }
        } catch (t: Throwable) {
            _uiState.value = _uiState.value.copy(isLoading = false, errorMsg = "系统错误: ${t.message}")
        }
    }
}

// 一键静默登录指定账号
internal fun MainViewModel.loginWithSpecificAccount(account: String) {
    val accounts = tokenManager.getRememberedAccounts()
    val target = accounts.find { it.account == account }
    if (target == null) {
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("该账号密码记录已失效"))
        return
    }
    login(target.account, target.password, rememberMe = false, isSilent = true)
}

// 切换账号（清除当前 Token 回到登录页）
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

/**
 * 静默签到 + 刷新余额（登录成功 / 自动登录时调用）。
 * 关键点：签到和拉余额都在【同一个 launch 内】完成，不层叠调用 refreshBalance()。
 * 400 already 已由 AuthRepository 视为成功，不阻断。
 * 遇 401 立即踢回登录页，不再白跑后续请求。
 */
internal fun MainViewModel.silentCheckInAndRefreshBalance() {
    viewModelScope.launch {
        val checkInResult = repo.checkIn()
        checkInResult.onSuccess { res ->
            // 【优化】如果确实领到了时长（今天第一次签到），给用户明确的正向反馈
            if (res.claimedSeconds > 0) {
                val min = res.claimedSeconds / 60
                val hours = min / 60
                val remainMin = min % 60
                val timeStr = if (hours > 0) "${hours}小时${remainMin}分钟" else "${min}分钟"

                _uiEvent.tryEmit(UiEvent.ShowSnackbar("今日签到成功，已获得 $timeStr 联机时长", isLong = true))
            }
            // 如果 claimedSeconds == 0，说明今天已经签过了（触发 400 already 兜底），保持静默
        }.onFailure { err ->
            if (handleAuthError(err)) return@launch
            MciLmNative.logApp("登录后静默签到失败(不阻断): ${err.message}")
        }

        // 无论是否签到，都拉取最新余额
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
            .onFailure { e -> if (!handleAuthError(e)) MciLmNative.logApp("获取权益失败: ${e.message}") }
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
            } else _uiEvent.tryEmit(UiEvent.ShowSnackbar("今日已签到"))
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
    return try { java.net.ServerSocket(port).use { true } } catch (e: Exception) { false }
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
    reconnectDeferred = null
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
        delay(500)
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
            .onSuccess { _uiEvent.tryEmit(UiEvent.ShowSnackbar("Token 已轮换，请保存好新值")) }
            .onFailure { e -> if (!handleAuthError(e)) _uiEvent.tryEmit(UiEvent.ShowSnackbar("Token 轮换失败: ${e.message}", isLong = true)) }
    }
}

internal fun MainViewModel.overrideToken(newToken: String) {
    if (newToken.isBlank()) {
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("Token 不能为空"))
        return
    }
    tokenManager.overrideToken(newToken.trim())
    _uiEvent.tryEmit(UiEvent.ShowSnackbar("Token 已覆盖，请重启 App 生效"))
    MciLmNative.logApp("开发者强制覆盖了 Token")
}