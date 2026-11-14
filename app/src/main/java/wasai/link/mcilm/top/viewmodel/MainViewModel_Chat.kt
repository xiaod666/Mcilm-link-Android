package wasai.link.mcilm.top.viewmodel

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope // 👈 关键修复
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch // 👈 关键修复
import org.json.JSONArray
import wasai.link.mcilm.top.data.AudioEngine
import wasai.link.mcilm.top.link.MciLmNative
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ===== 聊天与语音模块 =====

internal fun MainViewModel.sendChatMessage(text: String) {
    if (text.isBlank()) return
    viewModelScope.launch(Dispatchers.IO) {
        val safeText = text.replace("\n", " ").replace("\r", "")
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val newMsg = ChatMessageInfo("我", safeText, true, time)
        val now = System.currentTimeMillis()
        pendingSelfMessages[safeText] = now
        val cutoff = now - 60_000L
        pendingSelfMessages.filterValues { it < cutoff }.keys.forEach { pendingSelfMessages.remove(it) }
        _uiState.value = _uiState.value.copy(chatHistory = _uiState.value.chatHistory + newMsg)
        MciLmNative.linkCommand("chat say $safeText")
    }
}

internal fun MainViewModel.loadChatHistory() {
    viewModelScope.launch(Dispatchers.IO) {
        val jsonStr = MciLmNative.linkQuery("chat_history") ?: return@launch
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<ChatMessageInfo>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val from = o.optString("from", "未知")
                val text = o.optString("text", "")
                val isSelf = o.optBoolean("self", false) || from == "me"
                val unix = o.optLong("unix", 0L)
                val time = if (unix > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(unix * 1000)) else ""
                list.add(ChatMessageInfo(from, text, isSelf, time))
            }
            _uiState.value = _uiState.value.copy(chatHistory = list)
        } catch (_: Exception) {}
    }
}

internal fun MainViewModel.fetchMembers() {
    val jsonStr = MciLmNative.linkQuery("members") ?: return
    if (jsonStr.isEmpty()) return
    try {
        val root = org.json.JSONObject(jsonStr)
        val peers = root.optJSONArray("peers")
        if (peers != null) {
            val list = mutableListOf<MemberInfo>()
            for (i in 0 until peers.length()) {
                val p = peers.getJSONObject(i)
                list.add(MemberInfo(p.optString("peer_id", "未知"), p.optString("role", "guest"), p.optBoolean("online", true)))
            }
            _uiState.value = _uiState.value.copy(members = list)
        }
    } catch (_: Exception) {}
}

internal fun MainViewModel.kickMember(peerId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val res = MciLmNative.linkCommand("kick $peerId") ?: ""
        if (res.contains("失败") || res.contains("error", ignoreCase = true)) {
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("踢出失败: $res"))
        } else {
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("已踢出 $peerId"))
            fetchMembers()
        }
    }
}

internal fun MainViewModel.setChatMic(on: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
        MciLmNative.linkCommand("chat mic ${if (on) "on" else "off"}")
        delay(200)
        fetchNetworkStatus()
    }
}

internal fun MainViewModel.setChatSpeaker(on: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
        MciLmNative.linkCommand("chat speaker ${if (on) "on" else "off"}")
        delay(200)
        fetchNetworkStatus()
    }
}

internal fun MainViewModel.setChatVolume(vol: Int) {
    viewModelScope.launch(Dispatchers.IO) {
        MciLmNative.linkCommand("chat volume $vol")
        delay(200)
        fetchNetworkStatus()
    }
}

internal fun MainViewModel.hasMicPermission(): Boolean {
    return try {
        ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }
}

internal fun MainViewModel.ensureAudioForHost(features: String): String {
    if (!features.contains("chat=voice")) return features
    if (AudioEngine.start()) {
        val rc = MciLmNative.linkSetAudio(true)
        if (rc == 0) {
            _uiState.value = _uiState.value.copy(isVoiceActive = true)
            return features
        }
        MciLmNative.logApp("linkSetAudio 返回 $rc，尝试降级为文字聊天")
        AudioEngine.stop()
    }
    _uiEvent.tryEmit(UiEvent.ShowSnackbar("音频设备初始化失败，已降级为文字聊天", isLong = true))
    _uiState.value = _uiState.value.copy(isVoiceActive = false)
    return features.replace("chat=voice", "chat=text")
}

internal fun MainViewModel.enableVoice() {
    if (AudioEngine.isRunning()) {
        _uiState.value = _uiState.value.copy(isVoiceActive = true)
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("语音已开启"))
        return
    }
    if (!hasMicPermission()) {
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("请先授予麦克风权限", isLong = true))
        return
    }
    if (!AudioEngine.start()) {
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("音频设备初始化失败，请检查麦克风权限", isLong = true))
        return
    }
    if (MciLmNative.linkSetAudio(true) != 0) {
        AudioEngine.stop()
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("语音注入失败，当前内核可能不支持", isLong = true))
        return
    }
    _uiState.value = _uiState.value.copy(isVoiceActive = true)
    _uiEvent.tryEmit(UiEvent.ShowSnackbar("语音已开启"))
    MciLmNative.logApp("语音已手动开启")
}

internal fun MainViewModel.disableVoice() {
    if (!AudioEngine.isRunning()) {
        _uiState.value = _uiState.value.copy(isVoiceActive = false)
        return
    }
    MciLmNative.linkSetAudio(false)
    AudioEngine.stop()
    _uiState.value = _uiState.value.copy(isVoiceActive = false)
    _uiEvent.tryEmit(UiEvent.ShowSnackbar("语音已关闭（文字聊天不受影响）"))
    MciLmNative.logApp("语音已手动关闭")
}