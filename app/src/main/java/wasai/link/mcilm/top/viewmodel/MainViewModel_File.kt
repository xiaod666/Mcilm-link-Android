package wasai.link.mcilm.top.viewmodel

import android.app.Application // 👈 关键修复
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope // 👈 关键修复
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch // 👈 关键修复
import org.json.JSONArray
import wasai.link.mcilm.top.link.MciLmNative
import java.io.File

// ===== 文件同步模块 =====

internal fun MainViewModel.checkFileSyncSupport() {
    viewModelScope.launch(Dispatchers.IO) {
        val res = MciLmNative.linkCommand("fs tasks") ?: ""
        if (res.contains("未启用") || res.contains("没有开启") || res.contains("不支持")) {
            _uiState.value = _uiState.value.copy(isFileSyncSupported = false)
            MciLmNative.logApp("检测到当前房间未开启文件同步功能")
        } else {
            _uiState.value = _uiState.value.copy(isFileSyncSupported = true)
        }
    }
}

internal fun MainViewModel.dismissFileRequest() {
    val req = _uiState.value.pendingFileRequest ?: return
    viewModelScope.launch(Dispatchers.IO) { MciLmNative.linkCommand("fs refuse ${req.taskId}") }
    _uiState.value = _uiState.value.copy(pendingFileRequest = null)
}

internal fun MainViewModel.acceptFile(safUri: Uri? = null) {
    val req = _uiState.value.pendingFileRequest ?: return
    viewModelScope.launch(Dispatchers.IO) {
        val dir = File(getApplication<Application>().getExternalFilesDir(null), "downloads")
        if (!dir.exists()) dir.mkdirs()
        val safeName = taskNamesCache[req.taskId] ?: req.name
        val destFile = File(dir, safeName)

        if (safUri != null) { pendingSafExports[req.taskId] = safUri }

        MciLmNative.linkCommand("fs receive ${req.taskId} ${destFile.absolutePath}")
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("已接受文件，正在保存到: ${destFile.name}"))
        _uiState.value = _uiState.value.copy(pendingFileRequest = null)
    }
}

internal fun MainViewModel.cancelFile(taskId: String) {
    viewModelScope.launch(Dispatchers.IO) {
        MciLmNative.linkCommand("fs cancel $taskId")
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("已取消传输"))
        fetchFileTasks()
    }
}

internal fun MainViewModel.openFile(task: FileTaskInfo) {
    if (task.status == "done" || task.status == "completed") {
        val safeName = taskNamesCache[task.taskId] ?: task.name
        val file = File(getApplication<Application>().getExternalFilesDir(null), "downloads/$safeName")
        if (file.exists()) {
            try {
                val uri = FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                getApplication<Application>().startActivity(intent)
            } catch (e: Exception) {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("无法打开文件: ${e.message}"))
            }
        } else {
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("本地文件不存在，可能已被清理"))
        }
    }
}

internal fun MainViewModel.sendFile(uri: Uri) {
    if (!_uiState.value.isFileSyncSupported) {
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("当前房间未开启文件同步，请联系房主", isLong = true))
        return
    }
    viewModelScope.launch(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        var fileName = "upload_${System.currentTimeMillis()}"
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) fileName = cursor.getString(nameIndex) ?: fileName
            }
        }
        val cacheFile = File(getApplication<Application>().cacheDir, "outgoing_$fileName")
        resolver.openInputStream(uri)?.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
        MciLmNative.logApp("准备发送文件: ${cacheFile.absolutePath}")
        val res = MciLmNative.linkCommand("fs send ${cacheFile.absolutePath}") ?: ""
        MciLmNative.logApp("发送结果: $res")
        if (res.contains("没有开启") || res.contains("未启用") || res.contains("方向") || res.contains("不支持")) {
            _uiState.value = _uiState.value.copy(isFileSyncSupported = false)
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("房主未开启此方向的文件同步", isLong = true))
        }
    }
}

internal fun MainViewModel.fetchFileTasks() {
    val jsonStr = MciLmNative.linkQuery("fs_tasks") ?: return
    try {
        val arr = JSONArray(jsonStr)
        val list = mutableListOf<FileTaskInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val taskId = o.optString("id", "")
            var name = o.optString("name", "")

            if (name.isBlank() || name.startsWith("unknown")) {
                name = taskNamesCache[taskId] ?: "任务_${taskId.takeLast(6)}"
            } else {
                taskNamesCache[taskId] = name
            }

            list.add(
                FileTaskInfo(
                    taskId = taskId, name = name, direction = o.optString("direction", "unknown"),
                    status = o.optString("status", "unknown"), bytes = o.optLong("bytes", 0L), totalBytes = o.optLong("total_bytes", 0L)
                )
            )
        }
        _uiState.value = _uiState.value.copy(fileTasks = list)
    } catch (_: Exception) {}
}

internal suspend fun MainViewModel.exportSafFile(taskId: String, taskName: String, targetUri: Uri) {
    try {
        val safeName = taskNamesCache[taskId] ?: taskName
        val srcFile = File(getApplication<Application>().getExternalFilesDir(null), "downloads/$safeName")
        if (!srcFile.exists()) {
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("本地文件不存在，无法导出"))
            return
        }

        val resolver = getApplication<Application>().contentResolver
        resolver.openOutputStream(targetUri)?.use { output ->
            srcFile.inputStream().use { input -> input.copyTo(output) }
        }
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("已保存到选定目录"))
    } catch (e: Exception) {
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("文件保存失败: ${e.message}", isLong = true))
    }
}