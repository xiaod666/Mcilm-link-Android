package wasai.link.mcilm.top.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp // 👈 补上了这个关键的 import
import wasai.link.mcilm.top.ui.theme.DarkSurface
import wasai.link.mcilm.top.ui.theme.NeonGreen
import wasai.link.mcilm.top.viewmodel.ChatMessageInfo
import wasai.link.mcilm.top.viewmodel.FileTaskInfo
import wasai.link.mcilm.top.viewmodel.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(msg: ChatMessageInfo) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.isSelf) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (msg.isSelf) Alignment.End else Alignment.Start) {
            Text(
                text = if (msg.isSelf) "我" else msg.from,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 2.dp, start = 4.dp, end = 4.dp)
            )
            Surface(
                color = if (msg.isSelf) NeonGreen.copy(alpha = 0.2f) else DarkSurface,
                shape = RoundedCornerShape(8.dp),
                border = if (msg.isSelf) null else BorderStroke(1.dp, Color.DarkGray),
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = {
                        clipboardManager.setText(AnnotatedString(msg.text))
                        Toast.makeText(context, "已复制聊天内容", Toast.LENGTH_SHORT).show()
                    }
                )
            ) {
                Text(
                    text = msg.text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun FileTaskList(vm: MainViewModel, tasks: List<FileTaskInfo>) {
    if (tasks.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "文件传输任务",
            style = MaterialTheme.typography.labelMedium,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        tasks.forEach { task ->
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, Color.DarkGray),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (task.direction == "send") Icons.Default.Upload else Icons.Default.Download,
                            contentDescription = null,
                            tint = NeonGreen
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                task.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${task.direction} | ${task.status} | ${formatTraffic(task.bytes)} / ${formatTraffic(task.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (task.status == "error") MaterialTheme.colorScheme.error else Color.Gray
                            )
                        }

                        // 取消按钮
                        val isTransferring = task.status.contains("trans", ignoreCase = true) || task.status == "running"
                        val isDone = task.status.contains("done", ignoreCase = true) || task.status == "completed" || task.status == "success"

                        if (isTransferring) {
                            TextButton(onClick = { vm.cancelFile(task.taskId) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                // 修正为 12.sp
                                Text("取消", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }
                        } else if (isDone && task.direction != "send") {
                            // 打开按钮
                            TextButton(onClick = { vm.openFile(task) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                // 修正为 12.sp
                                Text("打开", color = NeonGreen, fontSize = 12.sp)
                            }
                        }
                    }
                    if (task.totalBytes > 0 && task.status != "error") {
                        val progress = (task.bytes.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = NeonGreen,
                            trackColor = Color.DarkGray
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatInputBar(
    onSend: (String) -> Unit,
    onAttachFile: () -> Unit,
    isFileSyncEnabled: Boolean = true,
    disabledToastText: String = "当前房间未开启文件同步功能"
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = {
                if (isFileSyncEnabled) onAttachFile()
                else Toast.makeText(context, disabledToastText, Toast.LENGTH_SHORT).show()
            }
        ) {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = "发送文件",
                tint = if (isFileSyncEnabled) NeonGreen else Color.DarkGray
            )
        }
        OutlinedTextField(
            value = inputText,
            onValueChange = { if (it.length <= 100) inputText = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("说点什么...", color = Color.Gray) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (inputText.isNotBlank()) { onSend(inputText); inputText = "" }
            }),
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = DarkSurface,
                focusedContainerColor = DarkSurface
            )
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = {
                if (inputText.isNotBlank()) {
                    onSend(inputText); inputText = ""; focusManager.clearFocus()
                }
            },
            modifier = Modifier.background(NeonGreen, RoundedCornerShape(50))
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送", tint = Color.Black)
        }
    }
}

fun formatTraffic(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}