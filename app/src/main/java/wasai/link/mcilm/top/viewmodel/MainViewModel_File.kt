package wasai.link.mcilm.top.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import wasai.link.mcilm.top.link.MciLmNative
import java.io.File

// ===== 文件同步模块 =====

private const val MAX_FILE_SIZE_BYTES = 100L * 1024 * 1024  // 100MB

/** 路径穿越防护：去掉目录分隔符和 ..，只保留文件名 */
private fun sanitizeFileName(raw: String): String {
    var name = raw
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace("..", "_")
        .trim()
    if (name.isBlank()) name = "unnamed_${System.currentTimeMillis()}.bin"
    if (name.length > 200) name = name.take(200)
    return name
}

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
    viewModelScope.launch(Dispatchers.IO) {
        MciLmNative.linkCommand("fs refuse ${req.taskId}")
    }
    _uiState.value = _uiState.value.copy(pendingFileRequest = null)
}

internal fun MainViewModel.acceptFile(safUri: Uri? = null) {
    val req = _uiState.value.pendingFileRequest ?: return
    viewModelScope.launch(Dispatchers.IO) {
        val dir = File(getApplication<Application>().getExternalFilesDir(null), "downloads")
        if (!dir.exists()) dir.mkdirs()
        // P0-8 修复：路径清理
        val safeName = sanitizeFileName(taskNamesCache[req.taskId] ?: req.name)
        val destFile = File(dir, safeName)

        if (safUri != null) pendingSafExports[req.taskId] = safUri

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
        val safeName = sanitizeFileName(taskNamesCache[task.taskId] ?: task.name)
        val file = File(getApplication<Application>().getExternalFilesDir(null), "downloads/$safeName")
        if (file.exists()) {
            try {
                val uri = FileProvider.getUriForFile(
                    getApplication(),
                    "${getApplication<Application>().packageName}.fileprovider",
                    file
                )
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
        try {
            val resolver = getApplication<Application>().contentResolver
            var fileName = "upload_${System.currentTimeMillis()}"
            var declaredSize = -1L

            // P0-6 修复：复制前查询 SIZE
            try {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: fileName
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            declaredSize = cursor.getLong(sizeIdx)
                        }
                    }
                }
            } catch (e: Exception) {
                MciLmNative.logApp("查询文件元信息失败: ${e.message}")
            }

            if (declaredSize > MAX_FILE_SIZE_BYTES) {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar(
                    "文件超过 100MB，当前引擎传输不稳定，建议分包或压缩后发送",
                    isLong = true
                ))
                return@launch
            }

            // P0-8 修复：路径清理 + 唯一前缀防覆盖
            val safeName = sanitizeFileName(fileName)
            val cacheFile = File(
                getApplication<Application>().cacheDir,
                "outgoing_${System.currentTimeMillis()}_$safeName"
            )

            // P0-7 修复：复制加 try-catch
            try {
                resolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                } ?: run {
                    _uiEvent.tryEmit(UiEvent.ShowSnackbar("无法读取文件内容"))
                    return@launch
                }
            } catch (e: Exception) {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("读取文件失败: ${e.message}", isLong = true))
                if (cacheFile.exists()) cacheFile.delete()
                return@launch
            }

            // 兜底：declaredSize 不可信时二次校验
            if (cacheFile.length() > MAX_FILE_SIZE_BYTES) {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar(
                    "文件超过 100MB，当前引擎传输不稳定，建议分包或压缩后发送",
                    isLong = true
                ))
                if (cacheFile.exists()) cacheFile.delete()
                return@launch
            }

            MciLmNative.logApp("准备发送文件: ${cacheFile.absolutePath}")
            val res = MciLmNative.linkCommand("fs send ${cacheFile.absolutePath}") ?: ""
            MciLmNative.logApp("发送结果: $res")

            if (res.contains("没有开启") || res.contains("未启用") ||
                res.contains("方向") || res.contains("不支持")) {
                _uiState.value = _uiState.value.copy(isFileSyncSupported = false)
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("房主未开启此方向的文件同步", isLong = true))
                if (cacheFile.exists()) cacheFile.delete()
            } else {
                launch {
                    delay(300_000L)
                    if (cacheFile.exists()) {
                        cacheFile.delete()
                        MciLmNative.logApp("清理发送缓存: ${cacheFile.name}")
                    }
                }
            }
        } catch (t: Throwable) {
            MciLmNative.logApp("sendFile 异常: ${t.message}")
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("发送失败: ${t.message}", isLong = true))
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
                    taskId = taskId, name = name,
                    direction = o.optString("direction", "unknown"),
                    status = o.optString("status", "unknown"),
                    bytes = o.optLong("bytes", 0L),
                    totalBytes = o.optLong("total_bytes", 0L)
                )
            )
        }
        _uiState.value = _uiState.value.copy(fileTasks = list)
    } catch (_: Exception) {}
}

internal suspend fun MainViewModel.exportSafFile(taskId: String, taskName: String, targetUri: Uri) {
    try {
        // P0-8 修复：路径清理
        val safeName = sanitizeFileName(taskNamesCache[taskId] ?: taskName)
        val srcFile = File(getApplication<Application>().getExternalFilesDir(null), "downloads/$safeName")
        if (!srcFile.exists()) {
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("本地文件不存在，无法导出"))
            return
        }

        val resolver = getApplication<Application>().contentResolver
        resolver.openOutputStream(targetUri)?.use { output ->
            srcFile.inputStream().use { input -> input.copyTo(output) }
        } ?: run {
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("无法打开目标位置"))
            return
        }
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("已保存到选定目录"))
    } catch (e: Exception) {
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("文件保存失败: ${e.message}", isLong = true))
    }
}