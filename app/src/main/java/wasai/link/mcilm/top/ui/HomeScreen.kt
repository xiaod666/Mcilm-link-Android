package wasai.link.mcilm.top.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    onNavigateAnnouncements: () -> Unit = {},
    onNavigateActivity: () -> Unit = {},
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
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

    // 使用 Scaffold 配合悬浮按钮
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            // 将原本在顶部的“运行日志”移到这里，使用小号悬浮按钮
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp) // 只留左右 padding，上下由 Scaffold 处理
        ) {
            Spacer(Modifier.height(16.dp))

            // ================= 顶部标题与图标 =================
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Mcilm 联机助手",
                    style = MaterialTheme.typography.headlineSmall, // 稍微调小字体防挤占
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 公告铃铛 + 未读角标
                    Box(modifier = Modifier.padding(end = 4.dp)) {
                        IconButton(onClick = onNavigateAnnouncements) {
                            Icon(Icons.Default.Notifications, contentDescription = "公告", tint = Color.White)
                        }
                        if (state.unreadAnnouncementCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-4).dp, y = 4.dp)
                                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.error,
                                        shape = RoundedCornerShape(percent = 50)
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (state.unreadAnnouncementCount > 99) "99+" else "${state.unreadAnnouncementCount}",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    lineHeight = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    // 设置齿轮
                    IconButton(onClick = onNavigateSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.White)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ================= 剩余时长卡片 =================
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

            // ================= 活动 Banner =================
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
                            Text("$unclaimedCount 个活动可领取奖励", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("点击查看并一键领取时长", color = Color.Gray, fontSize = 12.sp)
                        }
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = NeonGreen)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ================= 联机入口 =================
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

            // ================= 公开房间列表 =================
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
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(bottom = 80.dp) // 给悬浮按钮留出空间，防止遮挡最后一个房间
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
    }

    // 日志弹窗
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