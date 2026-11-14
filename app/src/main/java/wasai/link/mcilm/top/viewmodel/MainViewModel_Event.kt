package wasai.link.mcilm.top.viewmodel

import androidx.lifecycle.viewModelScope // 👈 关键修复
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch // 👈 关键修复
import org.json.JSONObject
import wasai.link.mcilm.top.data.AudioEngine
import wasai.link.mcilm.top.link.MciLmNative
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ===== 事件分发模块 =====

internal fun MainViewModel.observeEvents() {
    viewModelScope.launch {
        MciLmNative.events.collect { event ->
            when (event) {
                is MciLmNative.Event.Room -> {
                    val rid = event.roomId
                    if (!rid.isNullOrEmpty()) {
                        val p = lastHostParams
                        val pwd = if (p?.isPublic == true || p?.pass.isNullOrBlank()) null else p?.pass
                        _uiState.value = _uiState.value.copy(
                            isHosting = true, roomId = rid,
                            roomPassword = pwd,
                            isLoading = false,
                            serverEndpoint = resolveNodeName(p?.server ?: ""), localPort = p?.ports ?: "25565"
                        )
                        viewModelScope.launch(Dispatchers.IO) { repo.bindRoom(rid) }
                        startPatrol()
                        reconnectDeferred?.complete(true)
                    }
                }
                is MciLmNative.Event.Terminal -> {
                    stopPatrol()
                    reconnectDeferred?.complete(false)

                    if (AudioEngine.isRunning()) {
                        MciLmNative.linkSetAudio(false)
                        AudioEngine.stop()
                    }
                    var reason = ""
                    try {
                        val o = JSONObject(event.raw)
                        reason = o.optString("reason", "")
                    } catch (_: Exception) {}

                    if (event.kind == "fatal") {
                        MciLmNative.linkStop()
                        if (lastHostParams != null || lastJoinParams != null) {
                            _uiEvent.tryEmit(UiEvent.ShowSnackbar("网络异常断开，正在尝试重连...", isLong = true))
                            attemptReconnect()
                            return@collect
                        }
                    }

                    val msg = when (event.kind) {
                        "kicked" -> "您已被房主踢出房间" + if (reason.isNotBlank()) "（$reason）" else ""
                        "banned" -> "您的账号已被封禁/禁入，无法继续联机" + if (reason.isNotBlank()) "（$reason）" else ""
                        "room_closed" -> "房间已关闭/解散"
                        "fatal" -> "发生严重错误，联机已断开" + if (reason.isNotBlank()) "（$reason）" else ""
                        else -> "会话已终止 (${event.kind})"
                    }
                    _uiEvent.tryEmit(UiEvent.ShowSnackbar(msg, isLong = true))

                    _uiState.value = _uiState.value.copy(
                        isHosting = false, isJoining = false, roomId = null, roomPassword = null,
                        isLoading = false, errorMsg = null,
                        members = emptyList(), serverEndpoint = null, localPort = null,
                        chatHistory = emptyList(), isVoiceActive = false
                    )
                    viewModelScope.launch(Dispatchers.IO) { MciLmNative.linkStop() }
                }
                is MciLmNative.Event.Raw -> handleRawEvent(event)
                else -> Unit
            }
        }
    }
}

internal fun MainViewModel.handleRawEvent(event: MciLmNative.Event.Raw) {
    when (event.kind) {
        "chat_message" -> {
            try {
                val o = JSONObject(event.json)
                val from = o.optString("from", "未知")
                val text = o.optString("text", "")
                val isSelf = o.optBoolean("self", false) || from == "me"
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                val history = _uiState.value.chatHistory
                if (isSelf) {
                    if (pendingSelfMessages.remove(text) != null) return
                    val lastMsg = history.lastOrNull()
                    if (lastMsg != null && lastMsg.isSelf && lastMsg.text == text) return
                }
                _uiState.value = _uiState.value.copy(chatHistory = history + ChatMessageInfo(from, text, isSelf, time))
            } catch (_: Exception) {}
        }
        "file_sync_request" -> {
            try {
                val o = JSONObject(event.json)
                val taskId = o.optString("task_id", "")
                var name = o.optString("name", "")
                val from = o.optString("from", "未知")
                val bytes = o.optLong("bytes", 0L)

                if (name.isBlank() || name.startsWith("unknown")) {
                    val timeStr = SimpleDateFormat("MMdd_HHmm", Locale.getDefault()).format(Date())
                    name = "文件_${from.take(6)}_${timeStr}.bin"
                }

                if (taskId.isNotEmpty()) {
                    taskNamesCache[taskId] = name
                    _uiState.value = _uiState.value.copy(pendingFileRequest = PendingFileRequest(taskId, name, from, bytes))
                }
            } catch (_: Exception) {}
        }
        "file_sync_progress" -> {
            try {
                val o = JSONObject(event.json)
                val taskId = o.optString("task_id", "")
                val bytes = o.optLong("bytes", 0L)
                val totalBytes = o.optLong("total_bytes", 0L)
                if (taskId.isNotEmpty()) {
                    val newTasks = _uiState.value.fileTasks.map { t ->
                        if (t.taskId == taskId) t.copy(
                            bytes = bytes,
                            totalBytes = if (totalBytes > 0) totalBytes else t.totalBytes,
                            status = "transferring"
                        ) else t
                    }
                    _uiState.value = _uiState.value.copy(fileTasks = newTasks)
                }
            } catch (_: Exception) {}
        }
        "file_sync_done" -> {
            try {
                val o = JSONObject(event.json)
                val taskId = o.optString("task_id", "")
                var name = o.optString("name", "")
                if (name.isBlank() || name.startsWith("unknown")) {
                    name = taskNamesCache[taskId] ?: "未知文件"
                }

                if (taskId.isNotBlank() && pendingSafExports.containsKey(taskId)) {
                    val targetUri = pendingSafExports.remove(taskId)
                    if (targetUri != null) {
                        viewModelScope.launch(Dispatchers.IO) { exportSafFile(taskId, name, targetUri) }
                    }
                }
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("文件传输完成: $name"))
            } catch (_: Exception) {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("文件传输完成"))
            }
            fetchFileTasks()
        }
        "file_sync_error" -> {
            try {
                val o = JSONObject(event.json)
                val taskId = o.optString("task_id", "")
                val err = o.optString("error", "").ifBlank { o.optString("message", "未知错误") }
                var name = o.optString("name", "")
                if (name.isBlank() || name.startsWith("unknown")) {
                    name = taskNamesCache[taskId] ?: "未知文件"
                }

                if (taskId.isNotBlank()) pendingSafExports.remove(taskId)
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("文件传输失败（$name）: $err", isLong = true))
            } catch (_: Exception) {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("文件传输失败", isLong = true))
            }
            fetchFileTasks()
        }
        else -> Unit
    }
}