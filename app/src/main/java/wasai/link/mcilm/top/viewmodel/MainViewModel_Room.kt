package wasai.link.mcilm.top.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import wasai.link.mcilm.top.data.AudioEngine
import wasai.link.mcilm.top.link.MciLmNative
import kotlin.time.Duration.Companion.milliseconds

// ===== 联机控制模块 =====

internal fun MainViewModel.startHost(
    roomMeta: String, features: String, ports: String, pass: String = "",
    isPublic: Boolean = false, serverMode: ServerMode, customServer: String = "",
    minecraftPort: String = "", sni: String = "", plain: Boolean = false
) {
    if (isClickTooFast()) return
    if (_uiState.value.isLoading) return
    if (ports.isNotBlank()) {
        val p = ports.split(",").firstOrNull()?.trim()?.toIntOrNull()
        if (p == null || p !in 1024..65535) { _uiState.value = _uiState.value.copy(errorMsg = "端口不合规"); return }
        if (!isPortAvailable(p)) { _uiState.value = _uiState.value.copy(errorMsg = "端口 $p 被占用"); return }
    }
    if (minecraftPort.isNotBlank()) {
        val mp = minecraftPort.toIntOrNull()
        if (mp == null || mp !in 1..65535) { _uiState.value = _uiState.value.copy(errorMsg = "Minecraft 广播端口不合规"); return }
    }

    val server = when(serverMode) { ServerMode.DEFAULT -> ""; ServerMode.DIRECT -> customServer }
    lastHostParams = HostParams(roomMeta, features, ports, pass, isPublic, server, serverMode, minecraftPort, sni, plain)
    val token = repo.getLocalToken() ?: return
    _uiState.value = _uiState.value.copy(errorMsg = null, isLoading = true, loadingMsg = "正在校验额度并连接节点...")

    viewModelScope.launch(Dispatchers.IO) {
        if (isPublic) {
            val sponsorRes = repo.getSponsor().getOrNull()
            if (sponsorRes?.canPublicRoom != true) { _uiState.value = _uiState.value.copy(isLoading = false, errorMsg = "普通用户无法开启公开房"); return@launch }
        }
        val recentLogs = mutableListOf<String>()
        val logJob = launch { MciLmNative.logs.collect { recentLogs.add(it); if (recentLogs.size > 10) recentLogs.removeAt(0) } }
        try {
            repo.checkIn().onFailure { MciLmNative.logApp("开房前兜底签到失败(不阻断): ${it.message}") }
            val timeRes = repo.getRoomTime().getOrElse { e ->
                if (handleAuthError(e)) return@launch
                _uiState.value = _uiState.value.copy(isLoading = false, errorMsg = "查询额度失败: ${e.message}")
                return@launch
            }
            if (!timeRes.canHost) { _uiState.value = _uiState.value.copy(errorMsg = "时长已耗尽", isLoading = false); return@launch }

            val finalPass = if (isPublic) "" else pass
            if (server.isEmpty()) { MciLmNative.linkSetDiscovery(token, _uiState.value.discoveryThreshold) }
            applyExtraParams(minecraftPort)
            val effectiveFeatures = ensureAudioForHost(features)

            // 使用 CompletableDeferred 等待 room 事件
            val roomDeferred = CompletableDeferred<Boolean>()
            reconnectDeferred = roomDeferred

            val rc = MciLmNative.linkHostStart(server, roomMeta, "", finalPass, effectiveFeatures, ports, sni, if(plain) 1 else 0)
            if (rc != 0) {
                if (AudioEngine.isRunning()) { MciLmNative.linkSetAudio(false); AudioEngine.stop() }
                _uiState.value = _uiState.value.copy(isVoiceActive = false)
                delay(300.milliseconds)
                val cleanError = extractErrorLog(recentLogs)
                if (cleanError.contains("登录失败") || cleanError.contains("EOF") || cleanError.contains("连接已关闭")) {
                    val isOk = repo.checkAccountStatus()
                    if (!isOk) {
                        _uiState.value = _uiState.value.copy(errorMsg = "您的账号已被平台封禁，无法进行联机，请联系管理员", isLoading = false)
                        return@launch
                    }
                }
                _uiState.value = _uiState.value.copy(errorMsg = "开房失败:\n${getFriendlyError(cleanError)}", isLoading = false)
            } else {
                // 等待房间事件返回，最多等 6 秒
                val success = withTimeoutOrNull(6000L) { roomDeferred.await() } ?: false
                if (!success && _uiState.value.roomId == null) {
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMsg = "内核已就绪，但房间号未返回，请查看日志")
                }
            }
        } catch (t: Throwable) {
            if (AudioEngine.isRunning()) { MciLmNative.linkSetAudio(false); AudioEngine.stop() }
            _uiState.value = _uiState.value.copy(isLoading = false, isVoiceActive = false, errorMsg = "系统错误: ${t.message}")
        } finally { logJob.cancel() }
    }
}

internal fun MainViewModel.startClient(
    roomId: String, ports: String, pass: String = "", serverMode: ServerMode, customServer: String = "",
    sni: String = "", plain: Boolean = false, forceRelay: Boolean = false, preferPeers: Boolean = false
) {
    if (isClickTooFast()) return
    if (_uiState.value.isLoading) return
    if (ports.isNotBlank()) {
        val p = ports.split(",").firstOrNull()?.trim()?.toIntOrNull()
        if (p == null || p !in 1024..65535) { _uiState.value = _uiState.value.copy(errorMsg = "端口不合规"); return }
        if (!isPortAvailable(p)) { _uiState.value = _uiState.value.copy(errorMsg = "端口 $p 被占用"); return }
    }

    val server = when(serverMode) { ServerMode.DEFAULT -> ""; ServerMode.DIRECT -> customServer }
    lastJoinParams = ClientParams(roomId, ports, pass, server, serverMode, sni, plain, forceRelay, preferPeers)
    val token = repo.getLocalToken() ?: return
    _uiState.value = _uiState.value.copy(errorMsg = null, isJoining = true, isLoading = true, loadingMsg = "正在连接房间...")

    viewModelScope.launch(Dispatchers.IO) {
        val recentLogs = mutableListOf<String>()
        val logJob = launch { MciLmNative.logs.collect { recentLogs.add(it); if (recentLogs.size > 10) recentLogs.removeAt(0) } }
        try {
            if (server.isEmpty()) { MciLmNative.linkSetDiscovery(token, _uiState.value.discoveryThreshold) }
            val rc = MciLmNative.linkClientStart(server, roomId, "", pass, ports, sni, plain, forceRelay, preferPeers)
            if (rc != 0) {
                delay(300.milliseconds)
                val cleanError = extractErrorLog(recentLogs)
                if (cleanError.contains("登录失败") || cleanError.contains("EOF") || cleanError.contains("连接已关闭")) {
                    val isOk = repo.checkAccountStatus()
                    if (!isOk) {
                        _uiState.value = _uiState.value.copy(errorMsg = "您的账号已被平台封禁，无法进行联机，请联系管理员", isLoading = false, isJoining = false)
                        return@launch
                    }
                }
                _uiState.value = _uiState.value.copy(errorMsg = "加入失败:\n${getFriendlyError(cleanError)}", isJoining = false, isLoading = false)
            } else {
                // 轮询获取实际端口与聊天模式
                var mode = "text"
                var actualPort = ports
                repeat(3) {
                    try {
                        val o = JSONObject(MciLmNative.linkQuery("status") ?: "")
                        o.optJSONObject("chat")?.let { mode = it.optString("mode", "text") }
                        o.optJSONArray("local_ports")?.let {
                            if (it.length() > 0 && actualPort.isBlank()) {
                                actualPort = it.optString(0)
                            }
                        }
                        if (actualPort.isNotBlank()) return@repeat
                    } catch (_: Exception) {}
                    delay(300.milliseconds)
                }
                if (actualPort.isBlank()) actualPort = "25565"

                var voiceActive = false
                if (mode == "voice" && hasMicPermission()) {
                    if (AudioEngine.start() && MciLmNative.linkSetAudio(true) == 0) voiceActive = true else AudioEngine.stop()
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false, isJoining = true, roomId = roomId, roomPassword = if (pass.isBlank()) null else pass,
                    serverEndpoint = resolveNodeName(server), localPort = actualPort,
                    serverMode = serverMode, customServer = customServer, chatMode = mode, isVoiceActive = voiceActive
                )
                startPatrol()
            }
        } catch (t: Throwable) {
            if (AudioEngine.isRunning()) { MciLmNative.linkSetAudio(false); AudioEngine.stop() }
            _uiState.value = _uiState.value.copy(isLoading = false, isJoining = false, isVoiceActive = false, errorMsg = "系统错误: ${t.message}")
        } finally { logJob.cancel() }
    }
}

internal fun MainViewModel.attemptReconnect() {
    reconnectJob?.cancel()
    reconnectJob = viewModelScope.launch(Dispatchers.IO) {
        var attempts = 0
        while (attempts < 3) {
            attempts++
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("第 $attempts 次重连尝试...", isLong = true))
            MciLmNative.linkStop()
            delay(3000L)

            val host = lastHostParams; val client = lastJoinParams
            if (host == null && client == null) break
            reconnectDeferred = CompletableDeferred()

            val rc = if (host != null) {
                applyExtraParams(host.minecraftPort)
                MciLmNative.linkHostStart(host.server, host.roomMeta, "", host.pass, ensureAudioForHost(host.features), host.ports, host.sni, if (host.plain) 1 else 0)
            } else {
                MciLmNative.linkClientStart(client!!.server, client.roomId, "", client.pass, client.ports, client.sni, client.plain, client.forceRelay, client.preferPeers)
            }

            if (rc != 0) { delay(2000); continue }
            val success = withTimeoutOrNull(6000) { reconnectDeferred?.await() } ?: false
            if (success) { _uiEvent.tryEmit(UiEvent.ShowSnackbar("重连成功")); return@launch }
        }
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("重连失败，请等待十几秒后手动重试", isLong = true))
        stopSession()
    }
}

internal fun MainViewModel.stopSession() {
    stopPatrol()
    pendingSelfMessages.clear()
    if (AudioEngine.isRunning()) { MciLmNative.linkSetAudio(false); AudioEngine.stop() }
    _uiState.value = _uiState.value.copy(
        isHosting = false, isJoining = false, roomId = null, roomPassword = null,
        members = emptyList(), serverEndpoint = null, localPort = null, chatHistory = emptyList(),
        trafficUp = 0L, trafficDown = 0L, trafficPunchUp = 0L, trafficPunchDown = 0L, isVoiceActive = false,
        isTimeExhausted = false
    )
    viewModelScope.launch(Dispatchers.IO) { MciLmNative.linkStop() }
}

internal fun MainViewModel.logout() {
    stopPatrol()
    lastLoginParams = null; lastHostParams = null; lastJoinParams = null
    pendingSelfMessages.clear()
    if (AudioEngine.isRunning()) { MciLmNative.linkSetAudio(false); AudioEngine.stop() }
    viewModelScope.launch(Dispatchers.IO) { MciLmNative.linkStop() }
    repo.logout()
    _uiState.value = UiState(authState = AuthState.Unauthenticated, isDevMode = _uiState.value.isDevMode)
}

internal fun MainViewModel.retryLastAction() {
    if (isClickTooFast()) return
    _uiState.value = _uiState.value.copy(errorMsg = null)

    if (_uiState.value.authState == AuthState.Unauthenticated && lastLoginParams != null) {
        // 👇 修复编译报错，补上 rememberMe 参数
        login(lastLoginParams!!.first, lastLoginParams!!.second, rememberMe = false)
    } else if (lastHostParams != null) {
        startHost(
            lastHostParams!!.roomMeta, lastHostParams!!.features, lastHostParams!!.ports,
            lastHostParams!!.pass, lastHostParams!!.isPublic, lastHostParams!!.mode,
            lastHostParams!!.server, lastHostParams!!.minecraftPort, lastHostParams!!.sni, lastHostParams!!.plain
        )
    } else if (lastJoinParams != null) {
        startClient(
            lastJoinParams!!.roomId, lastJoinParams!!.ports, lastJoinParams!!.pass,
            lastJoinParams!!.mode, lastJoinParams!!.server,
            lastJoinParams!!.sni, lastJoinParams!!.plain, lastJoinParams!!.forceRelay, lastJoinParams!!.preferPeers
        )
    }
}

internal fun MainViewModel.applyExtraParams(minecraftPort: String) {
    val extras = if (minecraftPort.isNotBlank()) "minecraft=$minecraftPort" else ""
    val rc = MciLmNative.linkSetExtra(extras)
    if (rc != 0) MciLmNative.logApp("linkSetExtra('$extras') 返回 $rc")
}

internal fun MainViewModel.extractErrorLog(logs: List<String>): String {
    if (logs.isEmpty()) return "未知原因"
    val lastError = logs.lastOrNull { it.contains("ERROR") || it.contains("失败") } ?: logs.last()
    return lastError.replace(Regex("""^.*?(?:ERROR|INFO|WARN|DEBUG)\s*\(.*?\)\s*(?:\[.*?\]\s*)?"""), "").trim()
}

internal fun MainViewModel.getFriendlyError(cleanError: String): String {
    return when {
        cleanError.contains("unexpected EOF") || cleanError.contains("close 1006") || cleanError.contains("连接已关闭") ->
            "中心服务器异常断开了连接。\n\n这通常是因为账号被平台封禁/限制，或者当前网络节点被拦截。"
        cleanError.contains("连接密钥错误") -> "连接密钥校验失败，请确认该房间是否存在，或尝试切换节点/网络。"
        else -> cleanError
    }
}