package wasai.link.mcilm.top.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import wasai.link.mcilm.top.ui.theme.NeonGreen
import wasai.link.mcilm.top.viewmodel.*
import wasai.link.mcilm.top.viewmodel.ServerMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var roomIdInput by rememberSaveable { mutableStateOf("") }
    var passwordInput by rememberSaveable { mutableStateOf("") }
    var forceRelay by rememberSaveable { mutableStateOf(false) }
    var preferPeers by rememberSaveable { mutableStateOf(false) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents?.trim().orEmpty()
        if (raw.isNotEmpty()) {
            val (room, pass) = parseRoomText(raw)
            roomIdInput = room.take(80)
            if (pass.isNotBlank()) passwordInput = pass.take(20)
        }
    }

    var pendingJoinAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val preJoinMicLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingJoinAction?.invoke()
        pendingJoinAction = null
    }

    val checkLimit: (String, Int) -> String = { text, max ->
        if (text.length > max) text.take(max) else text
    }

    val isRoomIdValid = roomIdInput.isNotBlank() && roomIdInput.length <= 80
    val canSubmit = isRoomIdValid && !state.isLoading

    val trySubmit = {
        focusManager.clearFocus()
        // 【修复】同时兼容 "房间号 pass:密码" 与旧版复制出来的「房间号（密码：xxx）」
        val parsed = parseRoomText(roomIdInput)
        val targetRoom = parsed.first
        val targetPass = passwordInput.trim().ifBlank { parsed.second }

        if (targetRoom.isBlank()) {
            Toast.makeText(context, "房间 ID 不能为空", Toast.LENGTH_SHORT).show()
        } else {
            val doJoin = {
                vm.startClient(
                    roomId = targetRoom,
                    ports = "",
                    pass = targetPass,
                    serverMode = ServerMode.DEFAULT,
                    customServer = "",
                    forceRelay = forceRelay,
                    preferPeers = preferPeers
                )
            }

            val needMic = false
            if (needMic) {
                pendingJoinAction = doJoin
                preJoinMicLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            } else {
                doJoin()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("加入房间") },
                navigationIcon = {
                    IconButton(onClick = { if (!state.isLoading) onBack() }, enabled = !state.isLoading) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = roomIdInput,
                onValueChange = {
                    val cleaned = it.replace("\n", "")
                    val (room, pass) = parseRoomText(cleaned)
                    if (pass.isNotBlank()) {
                        roomIdInput = room.take(80)
                        passwordInput = pass.take(20)
                    } else {
                        roomIdInput = checkLimit(cleaned, 80)
                    }
                },
                label = { Text("房间 ID") },
                placeholder = { Text("输入房间号或扫码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = {
                        val options = ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt("扫描房间二维码")
                            setBeepEnabled(false)
                            setOrientationLocked(false)
                        }
                        scanLauncher.launch(options)
                    }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "扫码", tint = NeonGreen)
                    }
                },
                enabled = !state.isLoading
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = passwordInput,
                onValueChange = { passwordInput = checkLimit(it.replace("\n", ""), 20) },
                label = { Text("房间密码（如有）") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "切换密码可见性"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading
            )

            if (state.isDevMode) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "高级设置",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Switch(
                        checked = forceRelay,
                        onCheckedChange = { forceRelay = it },
                        enabled = !state.isLoading
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("强制中继模式", color = Color.White)
                        Text("跳过 P2P 打洞，适用于打洞失败时", color = Color.Gray, fontSize = 12.sp)
                    }
                }

                HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.5f))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Switch(
                        checked = preferPeers,
                        onCheckedChange = { preferPeers = it },
                        enabled = !state.isLoading
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("优先多跳连接", color = Color.White)
                        Text("优先尝试通过其他访客中转", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = { trySubmit() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = canSubmit
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("加入房间", style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 【修复】房间文本解析，统一二维码、粘贴、旧版复制三种来源：
 *  1. 二维码 / 新版复制：`房间号 pass:密码`
 *  2. 旧版复制：`房间号（密码：xxx）`
 *  3. 纯房间号（无密码）
 * 返回 (房间号, 密码)，没有密码时第二项为空串。
 */
private fun parseRoomText(raw: String): Pair<String, String> {
    val text = raw.trim()
    if (text.isEmpty()) return "" to ""

    // 分隔符按"越具体越靠前"排列，避免中文括号格式被当成普通冒号误切
    val separators = listOf(" pass:", "（密码：", "（密码:", "(密码：", "(密码:")
    for (sep in separators) {
        val idx = text.indexOf(sep)
        if (idx > 0) {
            val room = text.substring(0, idx).trim()
            val pass = text.substring(idx + sep.length)
                .trim()
                .trimEnd('）', ')')
                .trim()
            if (room.isNotEmpty()) return room to pass
        }
    }
    return text to ""
}