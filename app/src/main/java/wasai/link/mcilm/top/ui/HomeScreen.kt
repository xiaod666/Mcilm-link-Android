package wasai.link.mcilm.top.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wasai.link.mcilm.top.network.PublicRoom
import wasai.link.mcilm.top.network.UpdateState
import wasai.link.mcilm.top.ui.theme.DarkSurface
import wasai.link.mcilm.top.ui.theme.NeonGreen
import wasai.link.mcilm.top.viewmodel.*
import wasai.link.mcilm.top.viewmodel.ServerMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: MainViewModel,
    onNavigateHost: () -> Unit,
    onNavigateJoin: () -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateAnnouncements: () -> Unit = {},
    onNavigateActivity: () -> Unit = {},
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val updateDialogVisible by vm.updateDialogVisible.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var selectedRoomToJoin by remember { mutableStateOf<PublicRoom?>(null) }
    var showLogSheet by remember { mutableStateOf(false) }
    var pendingQuickJoin by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.fetchPublicRooms()
        vm.fetchAnnouncements(silent = true)
        vm.fetchActivities()
    }

    LaunchedEffect(state.isJoining) {
        if (state.isJoining && pendingQuickJoin) {
            pendingQuickJoin = false
            onNavigateJoin()
        }
    }

    if (selectedRoomToJoin != null) {
        val room = selectedRoomToJoin!!
        AlertDialog(
            onDismissRequest = { selectedRoomToJoin = null },
            title = { Text("加入房间") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("房间名称：${sanitizeText(room.roomName)}", fontWeight = FontWeight.Bold)
                    Text("当前人数：${room.players}/${room.maxPlayers}", color = Color.LightGray)
                    Text("节点：${sanitizeText(room.node ?: "未知")}", color = Color.LightGray)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedRoomToJoin = null
                    pendingQuickJoin = true
                    vm.startClient(room.roomId, "", "", ServerMode.DEFAULT, "")
                }) { Text("确认加入", color = NeonGreen, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { selectedRoomToJoin = null }) { Text("取消", color = Color.Gray) }
            },
            containerColor = DarkSurface
        )
    }

    // 更新对话框（发现新版本时显示）
    if (updateDialogVisible) {
        UpdateDialog(
            state = updateState,
            onDismiss = { vm.dismissUpdateDialog() },
            onDownload = { vm.startDownloadUpdate() },
            onInstall = { vm.installDownloadedApk() },
            onCancelDownload = { vm.cancelDownloadUpdate() }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            SmallFloatingActionButton(
                onClick = { showLogSheet = true },
                containerColor = DarkSurface,
                contentColor = NeonGreen,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Code, contentDescription = "运行日志")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Mcilm 联机助手", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 公告铃铛（原有）
                    Box(modifier = Modifier.padding(end = 4.dp)) {
                        IconButton(onClick = onNavigateAnnouncements) {
                            Icon(Icons.Default.Notifications, contentDescription = "公告", tint = Color.LightGray)
                        }
                        if (state.unreadAnnouncementCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-4).dp, y = 4.dp)
                                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(percent = 50))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (state.unreadAnnouncementCount > 99) "99+" else "${state.unreadAnnouncementCount}",
                                    color = Color.White, fontSize = 9.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // 更新红点（新增，仅在发现新版本时显示）
                    val hasUpdate = updateState is UpdateState.Available ||
                            updateState is UpdateState.Downloaded
                    if (hasUpdate) {
                        Box(modifier = Modifier.padding(end = 4.dp)) {
                            IconButton(onClick = { vm.showUpdateDialog() }) {
                                Icon(
                                    Icons.Default.SystemUpdate,
                                    contentDescription = "发现新版本",
                                    tint = NeonGreen
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-8).dp, y = 8.dp)
                                    .size(10.dp)
                                    .background(MaterialTheme.colorScheme.error, CircleShape)
                            )
                        }
                    }

                    // 设置（原有）
                    IconButton(onClick = onNavigateSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.LightGray)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().background(
                        Brush.linearGradient(colors = listOf(DarkSurface, NeonGreen.copy(alpha = 0.08f)))
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(NeonGreen, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text("当前时长余额", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(formatPlayTime(state.walletSeconds), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = NeonGreen)
                        }
                        Button(
                            onClick = { vm.checkIn() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen.copy(alpha = 0.15f), contentColor = NeonGreen),
                            border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.5f))
                        ) {
                            Text("每日签到", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            val unclaimedCount = state.activities.count { !it.claimed }
            if (unclaimedCount > 0) {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onNavigateActivity() },
                    colors = CardDefaults.cardColors(containerColor = NeonGreen.copy(alpha = 0.1f)),
                    border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = NeonGreen)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("$unclaimedCount 个活动奖励待领取", color = NeonGreen, fontWeight = FontWeight.Bold)
                            Text("点击前往活动中心，一键领取时长", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = NeonGreen)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text("开始联机", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                EntryCard(modifier = Modifier.weight(1f), title = "创建房间", desc = "作为房主开服", icon = Icons.Default.AddCircle, onClick = onNavigateHost)
                EntryCard(modifier = Modifier.weight(1f), title = "加入房间", desc = "输入 ID 联机", icon = Icons.AutoMirrored.Filled.Login, onClick = onNavigateJoin)
            }

            Spacer(Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(4.dp, 16.dp).background(NeonGreen, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(8.dp))
                    Text("公开房间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                }
                TextButton(onClick = { vm.fetchPublicRooms() }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("刷新", fontSize = 13.sp, color = NeonGreen)
                }
            }

            if (state.publicRooms.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Public, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("暂无公开房间", color = Color.Gray)
                    }
                }
            } else {
                val uniqueRooms = remember(state.publicRooms) { state.publicRooms.distinctBy { it.roomId } }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 80.dp, top = 8.dp)
                ) {
                    items(items = uniqueRooms, key = { it.roomId }) { room ->
                        PublicRoomCard(room) {
                            if (!isValidRoomId(room.roomId)) {
                                Toast.makeText(context, "房间信息异常", Toast.LENGTH_SHORT).show()
                                return@PublicRoomCard
                            }
                            if (room.maxPlayers > 0 && room.players >= room.maxPlayers) {
                                Toast.makeText(context, "房间已满", Toast.LENGTH_SHORT).show()
                                return@PublicRoomCard
                            }
                            selectedRoomToJoin = room
                        }
                    }
                }
            }
        }
    }

    if (showLogSheet) ModalBottomSheet(onDismissRequest = { showLogSheet = false }) { McilmLogSheetContent() }
}

private fun sanitizeText(text: String?): String {
    if (text.isNullOrBlank()) return "未知"
    return if (text.length > 20) text.take(20) + "..." else text
}

private fun isValidRoomId(roomId: String?): Boolean {
    if (roomId.isNullOrBlank()) return false
    return Regex("^[a-zA-Z0-9_-]+$").matches(roomId)
}

@Composable
fun EntryCard(
    modifier: Modifier = Modifier, title: String, desc: String, icon: ImageVector, onClick: () -> Unit
) {
    Card(
        modifier = modifier.height(120.dp).clip(RoundedCornerShape(16.dp)).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, Color.DarkGray.copy(alpha = 0.5f))
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(colors = listOf(NeonGreen.copy(alpha = 0.05f), Color.Transparent))
            )
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, contentDescription = title, tint = NeonGreen, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(desc, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

@Composable
fun PublicRoomCard(room: PublicRoom, onClick: () -> Unit) {
    val isFull = room.maxPlayers > 0 && room.players >= room.maxPlayers
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.DarkGray.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    sanitizeText(room.roomName),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isFull) Color.Gray else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("节点: ${sanitizeText(room.node)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(4.dp).background(Color.DarkGray, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isFull) "已满员" else "游戏中",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isFull) MaterialTheme.colorScheme.error else NeonGreen
                    )
                }
            }

            Surface(
                color = if (isFull) Color(0x33FF5252) else NeonGreen.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${room.players}/${room.maxPlayers}",
                        color = if (isFull) MaterialTheme.colorScheme.error else NeonGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

// ===== 更新对话框（内嵌，无需改 AppRoot） =====

@Composable
private fun UpdateDialog(
    state: UpdateState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit
) {
    if (state is UpdateState.Idle || state is UpdateState.Checking || state is UpdateState.NotAvailable) return

    AlertDialog(
        onDismissRequest = {
            // 下载中禁止关闭
            if (state !is UpdateState.Downloading) onDismiss()
        },
        title = {
            Text(
                when (state) {
                    is UpdateState.Available -> "发现新版本 ${state.info.versionName}"
                    is UpdateState.Downloading -> "正在下载更新"
                    is UpdateState.Downloaded -> "下载完成"
                    is UpdateState.Error -> "更新失败"
                    else -> "更新"
                },
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            when (state) {
                is UpdateState.Available -> {
                    Column {
                        Text(
                            state.info.releaseNotes.ifBlank { "（无更新说明）" }.take(800),
                            color = Color.LightGray,
                            maxLines = 15,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "安装包大小：${formatBytes(state.info.apkSize)}",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
                is UpdateState.Downloading -> {
                    Column {
                        // 自定义进度条（兼容 Material3 1.2.x，避免 API 差异）
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(Color.DarkGray, RoundedCornerShape(3.dp))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth((state.progress / 100f).coerceIn(0f, 1f))
                                    .height(6.dp)
                                    .background(NeonGreen, RoundedCornerShape(3.dp))
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "${state.progress}%   ${formatBytes(state.downloaded)} / ${formatBytes(state.total)}",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                }
                is UpdateState.Downloaded -> {
                    Text("即将拉起系统安装器", color = Color.LightGray)
                }
                is UpdateState.Error -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
                else -> Unit
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> TextButton(onClick = onDownload) {
                    Text("立即更新", color = NeonGreen, fontWeight = FontWeight.Bold)
                }
                is UpdateState.Downloaded -> TextButton(onClick = onInstall) {
                    Text("安装", color = NeonGreen, fontWeight = FontWeight.Bold)
                }
                is UpdateState.Error -> TextButton(onClick = onDownload) {
                    Text("重试", color = NeonGreen, fontWeight = FontWeight.Bold)
                }
                else -> Unit
            }
        },
        dismissButton = {
            when (state) {
                is UpdateState.Downloading -> TextButton(onClick = onCancelDownload) {
                    Text("取消", color = Color.Gray)
                }
                is UpdateState.Available, is UpdateState.Error -> TextButton(onClick = onDismiss) {
                    Text("稍后", color = Color.Gray)
                }
                else -> Unit
            }
        },
        containerColor = DarkSurface
    )
}

private fun formatBytes(b: Long): String = when {
    b >= 1024L * 1024L -> "%.1f MB".format(b / 1024.0 / 1024.0)
    b >= 1024L -> "%.1f KB".format(b / 1024.0)
    else -> "$b B"
}