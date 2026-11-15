package wasai.link.mcilm.top.viewmodel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import wasai.link.mcilm.top.link.MciLmNative
import wasai.link.mcilm.top.network.BusinessException

// ==================== 平台能力：公告 / 活动 / 邀请 / 反馈 ====================

internal fun MainViewModel.fetchAnnouncements(silent: Boolean = false) {
    if (_uiState.value.authState != AuthState.Authenticated) return
    viewModelScope.launch(Dispatchers.IO) {
        repo.getAnnouncements()
            .onSuccess { resp ->
                _uiState.value = _uiState.value.copy(
                    announcements = resp.list,
                    unreadAnnouncementCount = resp.list.count { !it.read }
                )
            }
            .onFailure { e ->
                if (!handleAuthError(e) && !silent) MciLmNative.logApp("获取公告失败: ${e.message}")
            }
    }
}

internal fun MainViewModel.markAnnouncementRead(id: Int) {
    val current = _uiState.value.announcements
    val target = current.firstOrNull { it.id == id } ?: return
    if (target.read) return
    val updated = current.map { if (it.id == id) it.copy(read = true) else it }
    _uiState.value = _uiState.value.copy(
        announcements = updated,
        unreadAnnouncementCount = updated.count { !it.read }
    )
    viewModelScope.launch(Dispatchers.IO) {
        repo.markAnnouncementRead(id).onFailure { e ->
            MciLmNative.logApp("标记公告已读失败(已乐观更新): ${e.message}")
        }
    }
}

internal fun MainViewModel.fetchActivities() {
    if (_uiState.value.authState != AuthState.Authenticated) return
    viewModelScope.launch(Dispatchers.IO) {
        repo.getActivities()
            .onSuccess { resp -> _uiState.value = _uiState.value.copy(activities = resp.list) }
            .onFailure { e -> if (!handleAuthError(e)) MciLmNative.logApp("获取活动失败: ${e.message}") }
    }
}

internal fun MainViewModel.claimActivity(id: Int) {
    if (isClickTooFast()) return
    viewModelScope.launch(Dispatchers.IO) {
        repo.claimActivity(id)
            .onSuccess { resp ->
                val updated = _uiState.value.activities.map { if (it.id == id) it.copy(claimed = true) else it }
                val gainedMin = resp.claimedSeconds / 60
                _uiState.value = _uiState.value.copy(activities = updated, walletSeconds = resp.walletSeconds)
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("领取成功！获得 $gainedMin 分钟时长"))
            }
            .onFailure { e ->
                if (handleAuthError(e)) return@launch
                if (e is BusinessException && e.code == "claimed") {
                    val updated = _uiState.value.activities.map { if (it.id == id) it.copy(claimed = true) else it }
                    _uiState.value = _uiState.value.copy(activities = updated)
                    _uiEvent.tryEmit(UiEvent.ShowSnackbar("该活动奖励已领取"))
                } else {
                    _uiEvent.tryEmit(UiEvent.ShowSnackbar("领取失败: ${e.message}", isLong = true))
                }
            }
    }
}

internal fun MainViewModel.fetchInviteInfo() {
    if (_uiState.value.authState != AuthState.Authenticated) return
    viewModelScope.launch(Dispatchers.IO) {
        repo.getInvite()
            .onSuccess { resp -> _uiState.value = _uiState.value.copy(inviteInfo = resp) }
            .onFailure { e -> if (!handleAuthError(e)) MciLmNative.logApp("获取邀请信息失败: ${e.message}") }
    }
}

internal fun MainViewModel.bindInviteCode(code: String) {
    if (code.isBlank()) {
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("邀请码不能为空")); return
    }
    if (isClickTooFast()) return
    viewModelScope.launch(Dispatchers.IO) {
        repo.bindInvite(code.trim())
            .onSuccess { resp ->
                val gainMin = resp.inviteeGain / 60
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("绑定成功！获得 $gainMin 分钟时长"))
                fetchInviteInfo()
            }
            .onFailure { e ->
                if (handleAuthError(e)) return@launch
                if (e is BusinessException && e.code == "bound") {
                    _uiEvent.tryEmit(UiEvent.ShowSnackbar("您已绑定过邀请码，每人仅限一次"))
                } else {
                    _uiEvent.tryEmit(UiEvent.ShowSnackbar("绑定失败: ${e.message}", isLong = true))
                }
            }
    }
}

internal fun MainViewModel.fetchFeedbackList() {
    if (_uiState.value.authState != AuthState.Authenticated) return
    viewModelScope.launch(Dispatchers.IO) {
        repo.getFeedbackList()
            .onSuccess { resp -> _uiState.value = _uiState.value.copy(feedbackList = resp.list) }
            .onFailure { e -> if (!handleAuthError(e)) MciLmNative.logApp("获取反馈列表失败: ${e.message}") }
    }
}

// 修复 P0-4：增加回调
internal fun MainViewModel.submitFeedback(category: String, content: String, contact: String, onResult: (Boolean) -> Unit) {
    if (content.trim().length < 5 || content.trim().length > 1000) {
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("反馈内容需 5~1000 字")); onResult(false); return
    }
    if (contact.trim().length > 64) {
        _uiEvent.tryEmit(UiEvent.ShowSnackbar("联系方式不能超过 64 字")); onResult(false); return
    }
    if (isClickTooFast()) { onResult(false); return }

    _uiState.value = _uiState.value.copy(isLoading = true, loadingMsg = "正在提交反馈...")
    viewModelScope.launch(Dispatchers.IO) {
        repo.submitFeedback(category, content.trim(), contact.trim().ifBlank { null })
            .onSuccess {
                _uiEvent.tryEmit(UiEvent.ShowSnackbar("反馈已提交，感谢您的支持"))
                fetchFeedbackList()
                onResult(true)
            }
            .onFailure { e ->
                if (!handleAuthError(e)) {
                    _uiEvent.tryEmit(UiEvent.ShowSnackbar("提交失败: ${e.message}", isLong = true))
                }
                onResult(false)
            }
        _uiState.value = _uiState.value.copy(isLoading = false)
    }
}