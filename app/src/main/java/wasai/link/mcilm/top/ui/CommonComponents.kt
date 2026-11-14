package wasai.link.mcilm.top.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import wasai.link.mcilm.top.data.LogFileManager
import wasai.link.mcilm.top.link.MciLmNative
import java.io.File

// ==================== [HomeScreen 需要调用的方法] ====================
fun formatPlayTime(seconds: Long): String {
    if (seconds <= 0) return "0 秒"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    val parts = mutableListOf<String>()
    if (h > 0) parts.add("${h} 小时")
    if (m > 0) parts.add("${m} 分钟")
    if (s > 0 || parts.isEmpty()) parts.add("${s} 秒")
    return parts.joinToString(" ")
}

// ==================== [HomeScreen 需要调用的弹窗] ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McilmLogSheetContent() {
    val logs = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val isAtBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible == null || lastVisible.index >= logs.size - 2
        }
    }

    LaunchedEffect(Unit) {
        logs.clear()
        logs.addAll(MciLmNative.appLogHistory)
        logs.addAll(MciLmNative.coreLogHistory)
        logs.sort()

        if (logs.isNotEmpty()) {
            listState.scrollToItem(logs.size - 1)
        }

        launch {
            MciLmNative.appLogs.collect { msg ->
                if (logs.size > 500) logs.removeAt(0)
                logs.add(msg)
                if (isAtBottom) listState.scrollToItem(logs.size - 1)
            }
        }
        launch {
            MciLmNative.logs.collect { msg ->
                if (logs.size > 500) logs.removeAt(0)
                logs.add(msg)
                if (isAtBottom) listState.scrollToItem(logs.size - 1)
            }
        }
    }

    // 封装分享文件的逻辑
    val shareFile = { file: File?, titlePrefix: String ->
        if (file != null && file.exists()) {
            try {
                if (file.length() == 0L) {
                    LogFileManager.write("--- 用户手动导出了日志 ---")
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "$titlePrefix - ${file.name}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "分享日志"))
            } catch (t: Throwable) {
                Toast.makeText(context, "分享失败: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "日志文件尚未生成或不存在", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().height(500.dp).background(Color(0xFF121212))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("运行日志 (历史+实时)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)

            Row {
                TextButton(onClick = {
                    shareFile(LogFileManager.getLogFile(), "本次运行日志")
                }) { Text("分享本次") }

                TextButton(onClick = {
                    shareFile(LogFileManager.getDebugFile(), "Debug报错日志")
                }) { Text("分享Debug", color = Color(0xFFFF5252)) }

                TextButton(onClick = {
                    logs.clear()
                    MciLmNative.appLogHistory.clear()
                    MciLmNative.coreLogHistory.clear()
                }) { Text("清空") }
            }
        }
        HorizontalDivider(color = Color.DarkGray)

        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无日志\n尝试登录或创建/加入房间后查看", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(logs) { log ->
                    val color = when {
                        log.contains("[App]") -> Color(0xFF00E5FF)
                        log.contains("ERROR") || log.contains("error") || log.contains("CRITICAL") -> Color(0xFFFF5252)
                        else -> Color(0xFFA0A0A0)
                    }
                    Text(log, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)
                }
            }
        }
    }
}