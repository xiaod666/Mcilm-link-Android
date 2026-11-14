package wasai.link.mcilm.top.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wasai.link.mcilm.top.ui.theme.DarkSurface
import wasai.link.mcilm.top.ui.theme.NeonGreen
import wasai.link.mcilm.top.viewmodel.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var bindCode by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.fetchInviteInfo() }

    val invite = state.inviteInfo

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("邀请好友") },
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
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (invite == null) {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (!invite.settings.enabled) {
                Card(colors = CardDefaults.cardColors(containerColor = DarkSurface)) {
                    Text("邀请功能暂未开启", color = Color.Gray, modifier = Modifier.padding(16.dp))
                }
            } else {
                // 邀请码卡
                Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("我的邀请码", color = Color.Gray, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(invite.code, color = NeonGreen, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                clipboard.setText(AnnotatedString(invite.code))
                                Toast.makeText(context, "邀请码已复制", Toast.LENGTH_SHORT).show()
                            }) { Text("复制码") }
                            OutlinedButton(onClick = {
                                clipboard.setText(AnnotatedString(invite.link))
                                Toast.makeText(context, "邀请链接已复制", Toast.LENGTH_SHORT).show()
                            }) { Text("复制链接") }
                        }
                    }
                }

                // 二维码
                if (invite.link.isNotBlank()) {
                    val qr = remember(invite.link) { QrCodeUtils.generateQrCode(invite.link) }
                    if (qr != null) {
                        Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("扫码注册（自动填入邀请码）", color = Color.Gray, fontSize = 12.sp)
                                Spacer(Modifier.height(10.dp))
                                Image(bitmap = qr.asImageBitmap(), contentDescription = "邀请二维码", modifier = Modifier.size(200.dp))
                            }
                        }
                    }
                }

                // 统计
                Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text("${invite.invitedCount}", color = NeonGreen, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text("已邀请人数", color = Color.Gray, fontSize = 12.sp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text("${invite.earnedSec / 60}", color = NeonGreen, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text("累计获得分钟", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }

                // 奖励规则
                Text(
                    "每邀请 1 人，邀请人得 ${invite.settings.inviterSec / 60} 分钟，被邀请人得 ${invite.settings.inviteeSec / 60} 分钟",
                    color = Color.LightGray, fontSize = 13.sp
                )

                // 绑定别人的邀请码（每人仅一次）
                if (invite.boundCode.isBlank()) {
                    Card(colors = CardDefaults.cardColors(containerColor = DarkSurface), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("绑定好友邀请码", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("每人仅限绑定一次", color = Color.Gray, fontSize = 12.sp)
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = bindCode,
                                    onValueChange = { bindCode = it.filter { c -> c.isLetterOrDigit() }.take(12) },
                                    label = { Text("邀请码") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = { vm.bindInviteCode(bindCode) }, enabled = bindCode.isNotBlank()) {
                                    Text("绑定")
                                }
                            }
                        }
                    }
                } else {
                    Text("已绑定邀请码：${invite.boundCode}", color = Color.Gray, fontSize = 13.sp)
                }
            }
        }
    }
}