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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wasai.link.mcilm.top.network.PublicRoom
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
    // [新增] 平台功能入口回调
    onNavigateAnnouncements: () -> Unit = {},
    onNavigateActivity: () -> Unit = {},
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showLogoutDialog by remember { mutableStateOf(false) }
    var selectedRoomToJoin by remember { mutableStateOf<PublicRoom?>(null) }
    var showLogSheet by remember { mutableStateOf(false) }
    var pendingQuickJoin by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.fetchPublicRooms()
        // [新增] 拉公告（未读角标）+ 活动（可领取 banner）
        vm.fetchAnnouncements(silent = true)
        vm.fetchActivities()
    }

    LaunchedEffect(state.isJoining) {
        if (state.isJoining && pendingQuickJoin) {
            pendingQuickJoin = false
            onNavigateJoin()
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("确认要退出当前账号吗？") },
            confirmButton = {
                TextButton(onClick = { showLogoutDialog = false; vm.logout() }) {
                    Text("确认", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            }
        )
    }

    if (selectedRoomToJoin != null) {
        val room = selectedRoomToJoin!!
        AlertDialog(
            onDismissRequest = { selectedRoomToJoin = null },
            title = { Text("加入房间") },
            text = {
                Column {
                    Text("房间名称：${sanitizeText(room.roomName)}")
                    Text("当前人数：${room.players}/${room.maxPlayers}")
                    Text("节点：${sanitizeText(room.node ?: "未知")}")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedRoomToJoin = null
                    pendingQuickJoin = true
                    vm.startClient(room.roomId, "", "", ServerMode.DEFAULT, "")
                }) { Text("确认加入", color = NeonGreen) }
            },
            dismissButton = {
                TextButton(onClick = { selectedRoomToJoin = null }) { Text("取消") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Mcilm 联机助手", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // [新增] 公告铃铛图标 + 未读角标
                Box {
                    IconButton(onClick = onNavigateAnnouncements) {
                        Icon(Icons.Default.Notifications, contentDescription = "公告", tint = Color.White)
                    }
                    if (state.unreadAnnouncementCount > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.error,
                            shape = CircleShape,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 6.dp, end = 6.dp)
                                .size(16.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    if (state.unreadAnnouncementCount > 9) "9+" else "${state.unreadAnnouncementCount}",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = onNavigateSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.White)
                }
                IconButton(onClick = { showLogSheet = true }) {
                    Icon(Icons.Default.List, contentDescription = "运行日志", tint = NeonGreen)
                }
                IconButton(onClick = { showLogoutDialog = true }) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "退出", tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("剩余时长", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                    Text(formatPlayTime(state.walletSeconds), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = NeonGreen)
                }
                Button(onClick = { vm.checkIn() }, shape = RoundedCornerShape(8.dp)) { Text("每日签到") }
            }
        }

        // [新增] 活动可领取 banner（仅在存在未领取活动时展示）
        val unclaimedCount = state.activities.count { !it.claimed }
        if (unclaimedCount > 0) {
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onNavigateActivity() },
                colors = CardDefaults.cardColors(containerColor = NeonGreen.copy(alpha = 0.15f)),
                border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = NeonGreen)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "$unclaimedCount 个活动可领取奖励",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "点击查看并一键领取时长",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = NeonGreen)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        Text("开始联机", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            EntryCard(
                modifier = Modifier.weight(1f), title = "创建房间", desc = "作为房主开服",
                icon = Icons.Default.AddCircle, onClick = onNavigateHost
            )
            EntryCard(
                modifier = Modifier.weight(1f), title = "加入房间", desc = "输入 ID 联机",
                icon = Icons.AutoMirrored.Filled.Login, onClick = onNavigateJoin
            )
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("公开房间 (${state.publicRooms.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            TextButton(onClick = { vm.fetchPublicRooms() }) { Text("刷新") }
        }

        if (state.publicRooms.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("暂无公开房间", color = Color.Gray)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                items(items = state.publicRooms, key = { it.roomId }) { room ->
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

    if (showLogSheet) ModalBottomSheet(onDismissRequest = { showLogSheet = false }) { McilmLogSheetContent() }
}

private fun sanitizeText(text: String?): String {
    if (text.isNullOrBlank()) return "未知"
    return if (text.length > 20) text.take(20) + "..." else text
}

private fun isValidRoomId(roomId: String?): Boolean {
    if (roomId.isNullOrBlank()) return false
    return Regex("^[a-zA-Z0-9-]+$").matches(roomId)
}

@Composable
fun EntryCard(
    modifier: Modifier = Modifier, title: String, desc: String, icon: ImageVector, onClick: () -> Unit
) {
    Card(
        modifier = modifier.height(120.dp).clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(16.dp)
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

@Composable
fun PublicRoomCard(room: PublicRoom, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(sanitizeText(room.roomName), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text("节点: ${sanitizeText(room.node)} | 人数: ${room.players}/${room.maxPlayers}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Icon(Icons.Default.Public, contentDescription = null, tint = NeonGreen)
        }
    }
}