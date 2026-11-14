package wasai.link.mcilm.top.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import wasai.link.mcilm.top.ui.theme.DarkSurface
import wasai.link.mcilm.top.ui.theme.NeonGreen
import wasai.link.mcilm.top.viewmodel.*
import wasai.link.mcilm.top.viewmodel.ServerMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var roomIdInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var forceRelay by remember { mutableStateOf(false) }
    var preferPeers by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    // 新版 ZXing 扫码结果回调
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

    // 麦克风预请求
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

            val needMic = false
            if (needMic) {
                pendingJoinAction = doJoin
                preJoinMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
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

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonGreen,
                            cursorColor = NeonGreen,
                            focusedLabelColor = NeonGreen
                        )
                    )

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = checkLimit(it.replace("\n", ""), 20) },
                        label = { Text("房间密码（如有）") },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonGreen,
                            cursorColor = NeonGreen,
                            focusedLabelColor = NeonGreen
                        )
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("高级设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("强制中继模式", color = Color.White)
                            Text("跳过 P2P 打洞，适用于打洞失败时", color = Color.Gray, fontSize = 12.sp)
                        }
                        Switch(checked = forceRelay, onCheckedChange = { forceRelay = it }, enabled = !state.isLoading)
                    }
                    HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("优先多跳连接", color = Color.White)
                            Text("优先尝试通过其他访客中转", color = Color.Gray, fontSize = 12.sp)
                        }
                        Switch(checked = preferPeers, onCheckedChange = { preferPeers = it }, enabled = !state.isLoading)
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
            Button(
                onClick = { trySubmit() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = canSubmit,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen,
                    contentColor = Color.Black
                )
            ) {
                Text("加入房间", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}