package wasai.link.mcilm.top.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import wasai.link.mcilm.top.link.MciLmNative
import wasai.link.mcilm.top.network.UpdateState
import java.io.File

// ===== 应用内更新模块（GitHub / Gitee 双源） =====

internal fun MainViewModel.checkForUpdate(silent: Boolean) {
    // Bug 2 修复：已有下载好的安装包时，不重新检查，避免覆盖 Downloaded 状态
    val snapshot = _updateState.value
    if (snapshot is UpdateState.Downloaded && snapshot.file.exists()) {
        if (!silent) _uiEvent.tryEmit(UiEvent.ShowSnackbar("已有下载好的安装包，请先安装"))
        return
    }
    if (updateJob?.isActive == true) return

    _updateState.value = UpdateState.Checking
    updateJob = viewModelScope.launch(Dispatchers.IO) {
        updateManager.checkForUpdate(force = !silent)
            .onSuccess { info ->
                if (info != null) {
                    lastAvailableInfo = info
                    _updateState.value = UpdateState.Available(info)
                    MciLmNative.logApp(
                        "[Update] 发现新版本 ${info.versionName}(${info.versionCode}) 来自 ${info.source}"
                    )
                    if (!silent) {
                        _uiEvent.tryEmit(UiEvent.ShowSnackbar("发现新版本 ${info.versionName}"))
                    }
                } else {
                    // Bug 3 修复：不覆盖 Available / Downloaded / Downloading
                    val cur = _updateState.value
                    val preserve = cur is UpdateState.Available ||
                            cur is UpdateState.Downloaded ||
                            cur is UpdateState.Downloading
                    if (!preserve) {
                        _updateState.value = UpdateState.NotAvailable
                    }
                    if (!silent && !preserve) {
                        _uiEvent.tryEmit(UiEvent.ShowSnackbar("当前已是最新版本"))
                    }
                }
            }
            .onFailure { e ->
                // Bug 3 修复：检查失败不覆盖已发现的新版本
                val cur = _updateState.value
                if (cur !is UpdateState.Available && cur !is UpdateState.Downloaded) {
                    _updateState.value = UpdateState.Error(e.message ?: "检查更新失败")
                }
                if (!silent) {
                    _uiEvent.tryEmit(
                        UiEvent.ShowSnackbar(
                            "检查更新失败（源: ${updateManager.currentSourceNames()}）: ${e.message}",
                            isLong = true
                        )
                    )
                }
            }
    }
}

internal fun MainViewModel.startDownloadUpdate() {
    // Bug 1 修复：从 Available 或 Error 状态都能拿到 info
    val info = when (val cur = _updateState.value) {
        is UpdateState.Available -> cur.info
        is UpdateState.Error -> lastAvailableInfo
        else -> null
    } ?: run {
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("无可用更新信息，请重新检查"))
        return
    }

    if (downloadJob?.isActive == true) return

    _updateState.value = UpdateState.Downloading(0, 0L, info.apkSize)
    downloadJob = viewModelScope.launch(Dispatchers.IO) {
        updateManager.downloadApk(info) { downloaded, total ->
            val progress = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
            val current = _updateState.value
            if (current is UpdateState.Downloading && current.progress == progress) {
                // 只更新字节数不刷新 UI 进度
                return@downloadApk
            }
            _updateState.value = UpdateState.Downloading(progress, downloaded, total)
        }.onSuccess { file ->
            _updateState.value = UpdateState.Downloaded(file, info)
            MciLmNative.logApp("[Update][${info.source}] 下载完成: ${file.absolutePath}")
            if (updateManager.needsInstallPermission()) {
                _uiEvent.tryEmit(
                    UiEvent.ShowSnackbar("请授予「安装未知应用」权限后自动安装", isLong = true)
                )
                updateManager.openInstallPermissionSettings()
            } else {
                installDownloadedApk(file)
            }
        }.onFailure { e ->
            _updateState.value = UpdateState.Error(e.message ?: "下载失败")
            MciLmNative.logApp("[Update][${info.source}] 下载失败: ${e.message}")
            _uiEvent.tryEmit(UiEvent.ShowSnackbar("下载失败: ${e.message}", isLong = true))
        }
    }
}

internal fun MainViewModel.cancelDownloadUpdate() {
    // 先中断阻塞中的 OkHttp Call，否则 http.newCall().execute() 会一直挂到 30s 超时
    updateManager.cancelInFlight()
    downloadJob?.cancel()
    downloadJob = null
    // Bug 2 修复：取消后恢复到 Available（红点仍在），而不是直接回到 Idle
    val info = lastAvailableInfo
    _updateState.value = if (info != null) UpdateState.Available(info) else UpdateState.Idle
}

internal fun MainViewModel.installDownloadedApk(file: File? = null) {
    val f = file ?: (_updateState.value as? UpdateState.Downloaded)?.file ?: return
    val ok = updateManager.installApk(f)
    if (!ok) {
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("安装器拉起失败，请检查权限", isLong = true))
    } else {
        MciLmNative.logApp("[Update] 已拉起系统安装器")
    }
}

internal fun MainViewModel.showUpdateDialog() {
    // 如果当前状态是 Idle / Checking / NotAvailable，点红点不应弹空对话框
    val cur = _updateState.value
    if (cur is UpdateState.Idle || cur is UpdateState.Checking || cur is UpdateState.NotAvailable) {
        // 兜底：从 lastAvailableInfo 恢复成 Available
        val info = lastAvailableInfo ?: return
        _updateState.value = UpdateState.Available(info)
    }
    _updateDialogVisible.value = true
}

internal fun MainViewModel.dismissUpdateDialog() {
    _updateDialogVisible.value = false
}