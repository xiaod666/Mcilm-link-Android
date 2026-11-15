package wasai.link.mcilm.top.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wasai.link.mcilm.top.ui.theme.DarkSurface
import wasai.link.mcilm.top.ui.theme.NeonGreen
import wasai.link.mcilm.top.viewmodel.*
import wasai.link.mcilm.top.viewmodel.ServerMode

@Composable
fun ClearIcon(text: String, onClear: () -> Unit) {
    if (text.isNotEmpty()) { IconButton(onClick = onClear) { Icon(Icons.Default.Clear, contentDescription = "清空") } }
}

private fun formatFeatures(features: String): String {
    if (features.isBlank()) return "无"
    val list = mutableListOf<String>()
    if (features.contains("file=both")) list.add("双向文件同步")
    else if (features.contains("file=push")) list.add("单向文件同步 (房主→访客)")
    else if (features.contains("file=pull")) list.add("单向文件同步 (访客→房主)")
    if (features.contains("chat=voice")) list.add("语音联机")
    else if (features.contains("chat=text")) list.add("文字聊天")
    return if (list.isEmpty()) "无" else list.joinToString(" / ")
}

@Composable
fun CustomFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(text = label, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        },
        shape = RoundedCornerShape(8.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = NeonGreen.copy(alpha = 0.15f),
            selectedLabelColor = NeonGreen,
            containerColor = Color.Transparent,
            labelColor = Color.LightGray
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected,
            borderColor = Color.Gray.copy(alpha = 0.5f),
            selectedBorderColor = NeonGreen,
            borderWidth = 1.dp, selectedBorderWidth = 1.dp
        )
    )
}

private val HostSubmitArgsSaver = mapSaver(
    save = { mapOf(
        "metaTrim" to it.metaTrim, "features" to it.features, "port" to it.port,
        "password" to it.password, "isPublic" to it.isPublic, "serverMode" to it.serverMode.name,
        "customServer" to it.customServer, "minecraftPort" to it.minecraftPort,
        "sni" to it.sni, "plain" to it.plain
    ) },
    restore = {
        HostSubmitArgs(
            metaTrim = it["metaTrim"] as String, features = it["features"] as String,
            port = it["port"] as String, password = it["password"] as String,
            isPublic = it["isPublic"] as Boolean, serverMode = ServerMode.valueOf(it["serverMode"] as String),
            customServer = it["customServer"] as String, minecraftPort = it["minecraftPort"] as String,
            sni = it["sni"] as String, plain = it["plain"] as Boolean
        )
    }
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var lastToastTime by rememberSaveable { mutableLongStateOf(0L) }

    LaunchedEffect(state.roomId) {
        if (state.roomId == null) vm.refreshSponsor()
    }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.sendFile(uri)
    }

    val createDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        if (uri != null) {
            vm.acceptFile(uri)
        } else {
            Toast.makeText(context, "未选择保存位置，已取消接收", Toast.LENGTH_SHORT).show()
        }
    }

    var pendingHostArgs by rememberSaveable(stateSaver = HostSubmitArgsSaver) {
        mutableStateOf(
            HostSubmitArgs("", "", "", "", false, ServerMode.DEFAULT, "", "", "", false)
        )
    }

    val preHostMicLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val p = pendingHostArgs
        if (granted) {
            vm.startHost(p.metaTrim, p.features, p.port, p.password, p.isPublic, p.serverMode, p.customServer, p.minecraftPort, p.sni, p.plain)
        } else {
            Toast.makeText(context, "麦克风权限被拒绝，将以纯文字模式开房", Toast.LENGTH_LONG).show()
            val textFeatures = p.features.replace("chat=voice", "chat=text")
            vm.startHost(p.metaTrim, textFeatures, p.port, p.password, p.isPublic, p.serverMode, p.customServer, p.minecraftPort, p.sni, p.plain)
        }
    }

    val inRoomMicLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.enableVoice()
        } else {
            Toast.makeText(context, "麦克风权限被拒绝，无法开启语音", Toast.LENGTH_LONG).show()
        }
    }

    val checkLimit: (String, Int, String) -> String = { text, max, name ->
        if (text.length > max) {
            val now = System.currentTimeMillis()
            if (now - lastToastTime > 1500) {
                Toast.makeText(context, "$name 超长已截断", Toast.LENGTH_SHORT).show()
                lastToastTime = now
            }
            text.take(max)
        } else text
    }

    var roomMeta by rememberSaveable { mutableStateOf("${android.os.Build.MODEL}的服务器:生存") }
    var maxOnline by rememberSaveable { mutableStateOf("5") }
    var hostPort by rememberSaveable { mutableStateOf("25565") }
    var hostPassword by rememberSaveable { mutableStateOf("") }
    var isPublic by rememberSaveable { mutableStateOf(false) }

    var serverMode by rememberSaveable { mutableStateOf(ServerMode.DEFAULT) }
    var customServer by rememberSaveable { mutableStateOf("") }

    var enableFileSync by rememberSaveable { mutableStateOf(false) }
    var fileSyncMode by rememberSaveable { mutableStateOf("both") }
    var enableChat by rememberSaveable { mutableStateOf(false) }
    var chatMode by rememberSaveable { mutableStateOf("text") }
    var enableLanBroadcast by rememberSaveable { mutableStateOf(false) }

    var checkingPerm by rememberSaveable { mutableStateOf(false) }
    var showPermissionDialog by rememberSaveable { mutableStateOf(false) }
    var showLogSheet by rememberSaveable { mutableStateOf(false) }
    var showStopDialog by rememberSaveable { mutableStateOf(false) }
    var showQrDialog by rememberSaveable { mutableStateOf(false) }
    var showMemberDialog by rememberSaveable { mutableStateOf(false) }
    var showChatControlDialog by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = state.roomId != null) { showStopDialog = true }

    val onlineInt = maxOnline.toIntOrNull()
    val onlineValid = when (serverMode) {
        ServerMode.DIRECT -> onlineInt != null && onlineInt in 1..999
        else              -> onlineInt != null && onlineInt in 1..state.roomMax
    }
    val portInt = hostPort.toIntOrNull()
    val portValid = portInt != null && portInt in 1024..65535
    val metaTrim = roomMeta.trim()
    val metaValid = metaTrim.contains(":") && metaTrim.split(":", limit = 2).let { it.size == 2 && it[0].isNotBlank() && it[1].isNotBlank() }
    val passValid = if (isPublic) true else hostPassword.isNotBlank() && hostPassword.length <= 20

    val directServerValid = if (serverMode == ServerMode.DIRECT) {
        val s = customServer.trim()
        val parts = s.split(":", limit = 3)
        if (parts.size < 2) false else {
            val host = parts[0]
            val port = parts[1].toIntOrNull()
            val key = parts.getOrNull(2) ?: ""
            val ipParts = host.split('.').map { it.toIntOrNull() }
            host.isNotBlank() && ipParts.size == 4 && ipParts.all { it != null && it in 0..255 } &&
                    port != null && port in 1..65535 && (key.isEmpty() || key.length in 1..64)
        }
    } else true

    val canSubmit = onlineValid && portValid && metaValid && passValid && directServerValid && !state.isLoading && !checkingPerm

    val qrText = remember(state.roomId, state.roomPassword) {
        if (state.roomId != null) {
            if (state.roomPassword.isNullOrBlank()) state.roomId!!
            else "${state.roomId} pass:${state.roomPassword}"
        } else ""
    }
    val copyText = remember(state.roomId, state.roomPassword) {
        if (state.roomId != null) {
            if (state.roomPassword.isNullOrBlank()) state.roomId!!
            else "${state.roomId}（密码：${state.roomPassword}）"
        } else ""
    }

    val features = buildString {
        append("online=$onlineInt")
        if (enableFileSync) append(",file=$fileSyncMode")
        if (enableChat) append(",chat=$chatMode")
    }

    val hostCanSendFile = enableFileSync && (fileSyncMode == "push" || fileSyncMode == "both")
    val fileDisabledMsg = if (enableFileSync) "当前配置为仅接收文件，不可发送" else "未开启文件同步"

    var sniInput by rememberSaveable { mutableStateOf("") }
    var plainWss by rememberSaveable { mutableStateOf(false) }

    val trySubmit = {
        focusManager.clearFocus()
        val minecraftPort = if (enableLanBroadcast) (portInt?.toString() ?: "") else ""
        pendingHostArgs = HostSubmitArgs(
            metaTrim, features, "$portInt", hostPassword, isPublic,
            serverMode, customServer.trim(), minecraftPort, sniInput, plainWss
        )
        val needMic = enableChat && chatMode == "voice" &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (needMic) {
            preHostMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            vm.startHost(metaTrim, features, "$portInt", hostPassword, isPublic, serverMode, customServer.trim(), minecraftPort, sniInput, plainWss)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("创建房间") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (!state.isLoading) {
                            // 修复 P1-8：走确认弹窗
                            if (state.roomId != null) {
                                showStopDialog = true
                            } else {
                                vm.stopSession(); onBack()
                            }
                        }
                    }, enabled = !state.isLoading) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp)) {
            if (state.roomId != null) {
                // ================== 房间已创建界面 ==================
                if (state.isTimeExhausted) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0x33F44336)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFF5252))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("房间时长已用尽", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold)
                                Text("新玩家将无法加入，已在房玩家不受影响",
                                    color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://link.mcilm.top/user/sponsor"))
                                )
                            }) { Text("去充值", color = Color(0xFFFF5252)) }
                        }
                    }
                }

                Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("房间 ID", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                Text(state.roomId!!, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                                if (!state.roomPassword.isNullOrBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("密码: ${state.roomPassword}", style = MaterialTheme.typography.bodySmall, color = NeonGreen)
                                }
                            }
                            Row {
                                Button(onClick = { showQrDialog = true }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.padding(end = 8.dp)) {
                                    Icon(Icons.Default.QrCode, contentDescription = "二维码", modifier = Modifier.size(16.dp))
                                }
                                Button(onClick = {
                                    clipboardManager.setText(AnnotatedString(copyText))
                                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                                }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "复制", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("复制", fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = Color.DarkGray)
                        Spacer(Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("节点: ${state.serverEndpoint ?: "自动分配"}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                Spacer(Modifier.width(6.dp))
                                if (state.nodePing >= 0) {
                                    val pingColor = when {
                                        state.nodePing < 60 -> NeonGreen
                                        state.nodePing < 120 -> Color(0xFFFFD740)
                                        else -> MaterialTheme.colorScheme.error
                                    }
                                    Text("(${state.nodePing}ms)", style = MaterialTheme.typography.bodySmall, color = pingColor, fontWeight = FontWeight.Bold)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("成员: ${state.members.size}人", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { showMemberDialog = true }, contentPadding = PaddingValues(0.dp)) {
                                    Text("管理成员", fontSize = 12.sp, color = NeonGreen)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("链路: ", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                Text(state.networkMode, style = MaterialTheme.typography.bodySmall, color = NeonGreen)
                            }
                            Row {
                                TextButton(onClick = { vm.switchNetworkMode(true) }, contentPadding = PaddingValues(0.dp), enabled = state.networkMode != "P2P 直连 (打洞)") { Text("切至 P2P", fontSize = 12.sp) }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { vm.switchNetworkMode(false) }, contentPadding = PaddingValues(0.dp), enabled = state.networkMode != "中继模式 (Relay)") { Text("切至 Relay", fontSize = 12.sp) }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("流量: 上行 ${formatTraffic(state.trafficUp)} | 下行 ${formatTraffic(state.trafficDown)}",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            if (state.chatMode == "voice") {
                                Text("🎙️ 麦克风: ${if (state.isVoiceActive) "已开启" else "未开启"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (state.isVoiceActive) NeonGreen else Color.Gray)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("已开启功能: ${formatFeatures(features)}", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp)) {
                    if (state.chatHistory.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无聊天消息", color = Color.Gray) }
                    } else {
                        val listState = rememberLazyListState()
                        LaunchedEffect(state.chatHistory.size) {
                            if (state.chatHistory.isNotEmpty()) listState.animateScrollToItem(state.chatHistory.size - 1)
                        }
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.chatHistory) { msg -> ChatBubble(msg) }
                        }
                    }
                }
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    FileTaskList(vm = vm, tasks = state.fileTasks)
                    Spacer(Modifier.height(8.dp))
                    ChatInputBar(
                        onSend = { vm.sendChatMessage(it) },
                        onAttachFile = { if (hostCanSendFile) filePickerLauncher.launch("*/*") },
                        isFileSyncEnabled = hostCanSendFile,
                        disabledToastText = fileDisabledMsg
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        if (state.chatMode == "voice") {
                            if (state.isVoiceActive) {
                                TextButton(onClick = { vm.disableVoice() }) {
                                    Text("🎙️ 开启中", color = NeonGreen)
                                }
                            } else {
                                TextButton(onClick = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        vm.enableVoice()
                                    } else {
                                        inRoomMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }) {
                                    Text("🎙️ 开启语音", color = NeonGreen)
                                }
                            }
                            TextButton(onClick = { showChatControlDialog = true }) {
                                Text("⚙️ 语音设置", color = Color.White)
                            }
                        } else {
                            Spacer(Modifier.width(1.dp))
                        }

                        Row {
                            TextButton(onClick = { showLogSheet = true }) { Text("运行日志") }
                            TextButton(onClick = { showStopDialog = true }) { Text("停止并解散房间", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            } else {
                // ================== 创建房间表单 ==================
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = roomMeta,
                        onValueChange = { roomMeta = checkLimit(it.replace("\n", ""), 50, "名称") },
                        label = { Text("房间名称:类型") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = roomMeta.isNotBlank() && !metaValid,
                        trailingIcon = { ClearIcon(roomMeta) { roomMeta = "" } },
                        enabled = !state.isLoading
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(
                            value = maxOnline,
                            onValueChange = { input ->
                                val filtered = input.filter { c -> c.isDigit() }.take(3)
                                val num = filtered.toIntOrNull()
                                if (serverMode != ServerMode.DIRECT && num != null && num > state.roomMax) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastToastTime > 1500) {
                                        Toast.makeText(context, "当前权益上限为 ${state.roomMax} 人", Toast.LENGTH_SHORT).show()
                                        lastToastTime = now
                                    }
                                    return@OutlinedTextField
                                }
                                maxOnline = filtered
                            },
                            label = { Text("人数") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                            isError = maxOnline.isNotEmpty() && !onlineValid,
                            enabled = !state.isLoading
                        )
                        OutlinedTextField(
                            value = hostPort,
                            onValueChange = { hostPort = checkLimit(it.filter { c -> c.isDigit() }, 5, "端口") },
                            label = { Text("端口") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                            isError = hostPort.isNotEmpty() && !portValid,
                            enabled = !state.isLoading
                        )
                    }
                    if (serverMode != ServerMode.DIRECT && onlineInt != null && onlineInt > state.roomMax) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "当前权益上限 ${state.roomMax} 人，超出将无法开房（赞助可提升）",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Switch(
                            checked = isPublic, enabled = !state.isLoading,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    checkingPerm = true
                                    vm.checkPublicPermission { hasPerm ->
                                        checkingPerm = false
                                        if (hasPerm) { isPublic = true; hostPassword = "" }
                                        else { showPermissionDialog = true; isPublic = false }
                                    }
                                } else isPublic = false
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("公开房间")
                        if (checkingPerm) {
                            Spacer(Modifier.width(8.dp))
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (!isPublic) {
                        OutlinedTextField(
                            value = hostPassword,
                            onValueChange = { hostPassword = checkLimit(it.replace("\n", ""), 20, "密码") },
                            label = { Text("房间密码 (必填)") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            enabled = !state.isLoading, isError = !passValid,
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = if (hostPassword.isBlank()) {
                                { Text("必须设置密码", color = MaterialTheme.colorScheme.error) }
                            } else null
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    Text("联机功能", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CustomFilterChip(selected = enableFileSync, onClick = { enableFileSync = !enableFileSync }, label = "文件同步")
                        CustomFilterChip(selected = enableChat, onClick = { enableChat = !enableChat }, label = "语音/文字聊天")
                        CustomFilterChip(selected = enableLanBroadcast, onClick = { enableLanBroadcast = !enableLanBroadcast }, label = "MC 局域网广播")
                    }
                    if (enableFileSync) {
                        Spacer(Modifier.height(12.dp))
                        Text("文件同步方向:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CustomFilterChip(selected = fileSyncMode == "push", onClick = { fileSyncMode = "push" }, label = "房主→访客")
                            CustomFilterChip(selected = fileSyncMode == "pull", onClick = { fileSyncMode = "pull" }, label = "访客→房主")
                            CustomFilterChip(selected = fileSyncMode == "both", onClick = { fileSyncMode = "both" }, label = "双向")
                        }
                    }
                    if (enableChat) {
                        Spacer(Modifier.height(12.dp))
                        Text("聊天模式:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CustomFilterChip(selected = chatMode == "text", onClick = { chatMode = "text" }, label = "纯文字")
                            CustomFilterChip(selected = chatMode == "voice", onClick = { chatMode = "voice" }, label = "语音联机")
                        }
                        if (chatMode == "voice") {
                            Text("语音联机将请求麦克风权限", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    if (enableLanBroadcast) {
                        Spacer(Modifier.height(12.dp))
                        Text("将向本机所在局域网广播 Minecraft 服务器端口 ($hostPort)，同局域网玩家在「多人游戏→局域网世界」可见本机",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Spacer(Modifier.height(16.dp))

                    Text("节点设置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.selectableGroup()) {
                        Row(Modifier.fillMaxWidth().height(48.dp).selectable(selected = serverMode == ServerMode.DEFAULT, onClick = { serverMode = ServerMode.DEFAULT }, role = Role.RadioButton), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = serverMode == ServerMode.DEFAULT, onClick = null)
                            Spacer(Modifier.width(8.dp)); Text("默认站点自动发现")
                        }
                        if (state.isDevMode) {
                            Row(Modifier.fillMaxWidth().height(48.dp).selectable(selected = serverMode == ServerMode.DIRECT, onClick = { serverMode = ServerMode.DIRECT }, role = Role.RadioButton), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = serverMode == ServerMode.DIRECT, onClick = null)
                                Spacer(Modifier.width(8.dp)); Text("直连 IP")
                            }
                            if (serverMode == ServerMode.DIRECT) {
                                OutlinedTextField(
                                    value = customServer,
                                    onValueChange = { customServer = checkLimit(it.replace("\n", ""), 80, "IP") },
                                    placeholder = { Text("IP:端口 或 IP:端口:密钥") },
                                    singleLine = true,
                                    isError = customServer.isNotBlank() && !directServerValid,
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.fillMaxWidth().padding(start = 40.dp, bottom = 8.dp)
                                )
                                if (customServer.isNotBlank() && !directServerValid) {
                                    Text("格式不正确，应为 IP:端口 或 IP:端口:密钥", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 40.dp))
                                }

                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 40.dp)) {
                                    Checkbox(checked = plainWss, onCheckedChange = { plainWss = it })
                                    Text("明文 WS (排障用)", style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedTextField(
                                    value = sniInput,
                                    onValueChange = { sniInput = it },
                                    label = { Text("自定义 SNI 域名 (留空默认)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(start = 40.dp, bottom = 8.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(32.dp))
                    Button(onClick = { trySubmit() }, modifier = Modifier.fillMaxWidth().height(50.dp), enabled = canSubmit) {
                        Text("启动房主")
                    }
                    Spacer(Modifier.height(24.dp))
                    TextButton(onClick = { showLogSheet = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("查看运行日志") }
                }
            }
        }
    }

    if (showQrDialog && state.roomId != null) {
        val qrBitmap = remember(state.roomId, state.roomPassword) { QrCodeUtils.generateQrCode(qrText) }
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("扫码加入") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (qrBitmap != null) Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "二维码", modifier = Modifier.size(250.dp))
                    else Text("二维码生成失败")
                    Spacer(Modifier.height(16.dp))
                    Text(copyText, style = MaterialTheme.typography.bodyMedium, color = NeonGreen)
                }
            },
            confirmButton = { TextButton(onClick = { showQrDialog = false }) { Text("关闭") } }
        )
    }

    if (showMemberDialog) {
        AlertDialog(
            onDismissRequest = { showMemberDialog = false },
            title = { Text("成员管理") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(state.members) { member ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text(member.peerId, color = Color.White)
                                Text(if (member.role == "host") "房主" else "访客", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            }
                            if (member.role != "host") {
                                TextButton(onClick = { vm.kickMember(member.peerId); showMemberDialog = false }) {
                                    Text("踢出", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMemberDialog = false }) { Text("关闭") } }
        )
    }

    if (showChatControlDialog) {
        AlertDialog(
            onDismissRequest = { showChatControlDialog = false },
            title = { Text("语音控制") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("麦克风", color = Color.White)
                        Switch(checked = state.chatMicOn, onCheckedChange = { vm.setChatMic(it) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("扬声器", color = Color.White)
                        Switch(checked = state.chatSpeakerOn, onCheckedChange = { vm.setChatSpeaker(it) })
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("音量: ${state.chatVolume}", color = Color.White)
                    Slider(
                        value = state.chatVolume.toFloat(),
                        onValueChange = { vm.setChatVolume(it.toInt().coerceIn(1, 10)) },
                        valueRange = 1f..10f,
                        steps = 8
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showChatControlDialog = false }) { Text("确定") } }
        )
    }

    if (state.pendingFileRequest != null) {
        val req = state.pendingFileRequest!!
        AlertDialog(
            onDismissRequest = { vm.dismissFileRequest() },
            title = { Text("文件接收请求") },
            text = {
                Text("来自 ${req.from} 的文件：\n\n文件名: ${req.name}\n大小: ${formatTraffic(req.bytes)}\n\n点击接受后请选择保存位置")
            },
            confirmButton = {
                TextButton(onClick = { createDocLauncher.launch(req.name) }) { Text("接受") }
            },
            dismissButton = { TextButton(onClick = { vm.dismissFileRequest() }) { Text("拒绝") } }
        )
    }

    if (showLogSheet) ModalBottomSheet(onDismissRequest = { showLogSheet = false }) { McilmLogSheetContent() }
    if (showPermissionDialog) AlertDialog(onDismissRequest = { showPermissionDialog = false }, title = { Text("权限不足") }, text = { Text("当前账号不支持开启公开房。") }, confirmButton = { TextButton(onClick = { showPermissionDialog = false }) { Text("知道了") } })
    if (showStopDialog) AlertDialog(
        onDismissRequest = { showStopDialog = false },
        title = { Text("停止房间") },
        text = { Text("确认解散当前房间吗？") },
        confirmButton = { TextButton(onClick = { showStopDialog = false; vm.stopSession(); onBack() }) { Text("确认", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { showStopDialog = false }) { Text("取消") } }
    )
}

private data class HostSubmitArgs(
    val metaTrim: String,
    val features: String,
    val port: String,
    val password: String,
    val isPublic: Boolean,
    val serverMode: ServerMode,
    val customServer: String,
    val minecraftPort: String,
    val sni: String,
    val plain: Boolean
)