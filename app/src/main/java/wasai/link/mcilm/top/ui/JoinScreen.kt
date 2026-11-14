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

    var roomIdInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var forceRelay by remember { mutableStateOf(false) }
    var preferPeers by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    // ZXing 扫码结果回调
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val raw = result.contents.trim()
            if (raw.contains(" pass:")) {
                val parts = raw.split(" pass:")
                roomIdInput = parts[0]
                passwordInput = parts.getOrElse(1) { "" }
            } else {
                roomIdInput = raw
            }
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
        var targetRoom = roomIdInput.trim()
        var targetPass = passwordInput.trim()

        if (targetRoom.contains(" pass:")) {
            val parts = targetRoom.split(" pass:")
            targetRoom = parts[0]
            if (targetPass.isBlank()) targetPass = parts.getOrElse(1) { "" }
        }

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

            // 预留麦克风预检接口
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

            // 房间 ID
            OutlinedTextField(
                value = roomIdInput,
                onValueChange = {
                    if (it.contains(" pass:")) {
                        val parts = it.split(" pass:")
                        roomIdInput = parts[0]
                        passwordInput = parts.getOrElse(1) { "" }
                    } else {
                        roomIdInput = checkLimit(it.replace("\n", ""), 80)
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

            // 房间密码
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

            // ==================== 开发者模式专属：高级设置 ====================
            if (state.isDevMode) {
                Spacer(Modifier.height(24.dp))

                Text(
                    "高级设置",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                // 强制中继
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

                // 优先多跳
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

            // 加入房间按钮
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