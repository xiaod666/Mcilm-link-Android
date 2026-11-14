package wasai.link.mcilm.top.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wasai.link.mcilm.top.BuildConfig
import wasai.link.mcilm.top.link.MciLmNative
import wasai.link.mcilm.top.ui.theme.DarkSurface
import wasai.link.mcilm.top.ui.theme.NeonGreen
import wasai.link.mcilm.top.viewmodel.*
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    // [新增] 平台功能入口回调（默认空 lambda，向后兼容旧调用）
    onNavigateAnnouncements: () -> Unit = {},
    onNavigateActivity: () -> Unit = {},
    onNavigateInvite: () -> Unit = {},
    onNavigateFeedback: () -> Unit = {},
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var isExternalLib by remember { mutableStateOf(File(context.filesDir, "lib/libmcilm_link.so").exists()) }
    var kernelVersion by remember { mutableStateOf(MciLmNative.linkVersion() ?: "未知版本") }
    LaunchedEffect(state.isDevMode) {
        isExternalLib = File(context.filesDir, "lib/libmcilm_link.so").exists()
        kernelVersion = MciLmNative.linkVersion() ?: "未知版本"
    }

    // [新增] 进设置页拉一次平台数据（用于角标提示）
    LaunchedEffect(Unit) {
        vm.fetchAnnouncements(silent = true)
        vm.fetchActivities()
        vm.fetchInviteInfo()
    }

    var clickCount by remember { mutableIntStateOf(0) }
    var showDevDialog by remember { mutableStateOf(false) }
    var devKeyInput by remember { mutableStateOf("") }
    var showDevKey by remember { mutableStateOf(false) }

    var thresholdInput by remember { mutableStateOf(state.discoveryThreshold.toString()) }

    var showTokenDialog by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }

    LaunchedEffect(clickCount) {
        if (clickCount > 0) {
            kotlinx.coroutines.delay(2.seconds)
            if (clickCount < 5) clickCount = 0
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("应用信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeonGreen)
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("应用版本", color = Color.Gray)
                        Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Color.White)
                    }
                    HorizontalDivider(color = Color.DarkGray)
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("内核版本", color = Color.Gray)
                        Text(kernelVersion, color = Color.White)
                    }
                }
            }

            // ==================== [新增] 平台功能 ====================
            Spacer(Modifier.height(24.dp))
            Text("平台功能", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeonGreen)
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    // 公告
                    PlatformEntryRow(
                        title = "公告",
                        badgeText = if (state.unreadAnnouncementCount > 0) "${state.unreadAnnouncementCount}" else null,
                        onClick = onNavigateAnnouncements
                    )
                    HorizontalDivider(color = Color.DarkGray)
                    // 活动中心（有未领取才显示角标）
                    val unclaimedCount = state.activities.count { !it.claimed }
                    PlatformEntryRow(
                        title = "活动中心",
                        badgeText = if (unclaimedCount > 0) "$unclaimedCount" else null,
                        onClick = onNavigateActivity
                    )
                    // 邀请好友：功能未开启时隐藏
                    if (state.inviteInfo?.settings?.enabled == true) {
                        HorizontalDivider(color = Color.DarkGray)
                        PlatformEntryRow(
                            title = "邀请好友",
                            subtitle = "每邀 1 人得 ${state.inviteInfo!!.settings.inviterSec / 60} 分钟",
                            onClick = onNavigateInvite
                        )
                    }
                    HorizontalDivider(color = Color.DarkGray)
                    // 意见反馈
                    PlatformEntryRow(
                        title = "意见反馈",
                        onClick = onNavigateFeedback
                    )
                }
            }
            // ==================== 平台功能结束 ====================

            Spacer(Modifier.height(24.dp))
            Text("内核信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeonGreen)
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                clickCount++
                                if (clickCount == 5) {
                                    clickCount = 0
                                    if (!state.isDevMode) showDevDialog = true
                                    else Toast.makeText(context, "开发者模式已开启", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("当前加载模式", color = Color.Gray)
                        Text(if (isExternalLib) "外置库 (私有目录)" else "内置库 (APK自带)",
                            color = if (isExternalLib) NeonGreen else Color.White)
                    }
                    if (state.isDevMode) {
                        HorizontalDivider(color = Color.DarkGray)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("开发者模式", color = Color.Gray)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("已开启", color = NeonGreen)
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    onClick = {
                                        vm.disableDeveloperMode()
                                        Toast.makeText(context, "开发者模式已关闭", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) { Text("关闭", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }

            if (state.isDevMode) {
                Spacer(Modifier.height(24.dp))
                Text("高级配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = NeonGreen)
                Spacer(Modifier.height(16.dp))

                Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = thresholdInput,
                                onValueChange = { thresholdInput = it.filter { c -> c.isDigit() }.take(4) },
                                label = { Text("发现阈值 (ms)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            Spacer(Modifier.width(16.dp))
                            Button(onClick = {
                                val num = thresholdInput.toIntOrNull() ?: 150
                                vm.updateThreshold(num)
                                Toast.makeText(context, "阈值已更新", Toast.LENGTH_SHORT).show()
                            }) { Text("保存") }
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { vm.clearPrivateLib() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("清理私有库缓存 (下次启动重载)") }

                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { showTokenDialog = true; tokenInput = ""; tokenVisible = false },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("强制覆盖 Token") }

                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                vm.rotateToken()
                                Toast.makeText(context, "正在请求轮换...", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("轮换持久 Token (旧值立即失效)") }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showDevDialog) {
        AlertDialog(
            onDismissRequest = { showDevDialog = false; devKeyInput = ""; showDevKey = false },
            title = { Text("开发者认证") },
            text = {
                Column {
                    Text("请输入开发者激活码以开启隐藏功能：", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = devKeyInput,
                        onValueChange = { devKeyInput = it.trim() },
                        singleLine = true,
                        visualTransformation = if (showDevKey) VisualTransformation.None else PasswordVisualTransformation(),
                        label = { Text("激活码") },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showDevKey = !showDevKey }) {
                                    Icon(if (showDevKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                                }
                                ClearIcon(devKeyInput) { devKeyInput = "" }
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val hash = try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        digest.digest(devKeyInput.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                    } catch (_: Exception) { "" }

                    if (hash == "19cfdfdb1e95f4fbfb047de51a26fd90c2db76f1555370b74ba4f8671153dae0") {
                        vm.enableDeveloperMode()
                        Toast.makeText(context, "开发者模式已激活", Toast.LENGTH_LONG).show()
                        showDevDialog = false; showDevKey = false
                    } else {
                        Toast.makeText(context, "激活码错误", Toast.LENGTH_SHORT).show()
                    }
                    devKeyInput = ""
                }) { Text("验证") }
            },
            dismissButton = { TextButton(onClick = { showDevDialog = false; devKeyInput = ""; showDevKey = false }) { Text("取消") } }
        )
    }

    if (showTokenDialog) {
        AlertDialog(
            onDismissRequest = { showTokenDialog = false; tokenInput = "" },
            title = { Text("强制覆盖 Token") },
            text = {
                Column {
                    Text("将覆盖本地存储的 mci_ 持久 Token，需重启 App 生效。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it.trim() },
                        singleLine = true,
                        label = { Text("mci_...") },
                        visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                    Icon(if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                                }
                                ClearIcon(tokenInput) { tokenInput = "" }
                            }
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.overrideToken(tokenInput)
                    showTokenDialog = false
                    tokenInput = ""
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showTokenDialog = false; tokenInput = "" }) { Text("取消") } }
        )
    }
}

// ==================== [新增] 平台功能入口行组件 ====================
@Composable
private fun PlatformEntryRow(
    title: String,
    subtitle: String? = null,
    badgeText: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            if (subtitle != null) {
                Text(subtitle, color = Color.Gray, fontSize = 12.sp)
            }
        }
        if (badgeText != null) {
            Surface(
                color = MaterialTheme.colorScheme.error,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    badgeText,
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(14.dp)
        )
    }
}