package wasai.link.mcilm.top.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import wasai.link.mcilm.top.ui.theme.NeonGreen
import java.io.File

/** 日志面板最多保留的行数（同时也是内存上限，超出后从头部丢弃） */
private const val MAX_LOG_LINES = 1000

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

// ==================== 日志查看器 ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McilmLogSheetContent() {
    val logs = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    var autoScroll by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0=全部, 1=App, 2=内核, 3=报错
    // 追加计数：达到上限后列表是"删一条加一条"，size 不再变化，
    // 因此自动滚动必须依赖这个单调递增的版本号，而不是 size。
    var logRevision by remember { mutableIntStateOf(0) }

    // 【修复】不再用 remember(logs.size, selectedTab) 缓存过滤结果。
    // 日志到 1000 条上限后是"先 removeAt(0) 再 add"，size 恒定不变，
    // 旧写法导致 remember 的 key 不再变化，界面从此卡在旧列表上。
    // logs 是 SnapshotStateList，直接在组合期过滤即可自然响应增删。
    val filteredLogs = when (selectedTab) {
        1 -> logs.filter { it.contains("[App]") }
        2 -> logs.filter { it.contains("[Core]") }
        3 -> logs.filter {
            it.contains("ERROR", true) || it.contains("失败") ||
                it.contains("Exception", true) || it.contains("[Error]", true)
        }
        else -> logs.toList()
    }

    LaunchedEffect(Unit) {
        logs.clear()
        logs.addAll(MciLmNative.appLogHistory)
        logs.addAll(MciLmNative.coreLogHistory)
        logs.sort()
        logRevision++

        // 首帧不在这里滚动：此时 LazyColumn 还没被组合出来（会被空态分支占位），
        // 交给下面按 logRevision 触发的自动滚动协程处理即可，避免 scrollToItem(-1)。
        launch {
            MciLmNative.appLogs.collect { msg ->
                if (logs.size >= MAX_LOG_LINES) logs.removeAt(0)
                logs.add(msg)
                logRevision++
            }
        }
        launch {
            MciLmNative.logs.collect { msg ->
                if (logs.size >= MAX_LOG_LINES) logs.removeAt(0)
                logs.add(msg)
                logRevision++
            }
        }
    }

    LaunchedEffect(logRevision, autoScroll, selectedTab) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            // 高频日志下 animateScrollToItem 会累积动画队列，瞬时定位更稳定
            listState.scrollToItem(filteredLogs.lastIndex)
        }
    }

    val shareFile = { file: File?, titlePrefix: String ->
        if (file != null && file.exists()) {
            try {
                if (file.length() == 0L) LogFileManager.write("--- 用户手动导出了日志 ---")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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
            Toast.makeText(context, "日志文件尚未生成或为空", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f).background(Color(0xFF121212))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("运行日志", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Row {
                IconButton(onClick = { autoScroll = !autoScroll }) {
                    Icon(
                        if (autoScroll) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (autoScroll) "暂停滚动" else "恢复滚动",
                        tint = if (autoScroll) Color.Gray else NeonGreen
                    )
                }
                TextButton(onClick = { shareFile(LogFileManager.getLogFile(), "本次运行日志") }) { Text("分享本次", fontSize = 13.sp) }
                TextButton(onClick = { shareFile(LogFileManager.getDebugFile(), "Debug日志") }) { Text("分享Debug", color = Color(0xFFFF5252), fontSize = 13.sp) }
                TextButton(onClick = {
                    logs.clear()
                    MciLmNative.appLogHistory.clear()
                    MciLmNative.coreLogHistory.clear()
                }) { Text("清空", color = Color.Gray, fontSize = 13.sp) }
            }
        }

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF1E1E1E),
            contentColor = NeonGreen,
            divider = { HorizontalDivider(color = Color.DarkGray) }
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("全部") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("应用") })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("内核") })
            Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text("报错", color = if (selectedTab == 3) NeonGreen else Color(0xFFFF5252)) })
        }

        if (filteredLogs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无相关日志", color = Color.Gray)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredLogs) { log ->
                    val isError = log.contains("ERROR", true) || log.contains("Exception", true) || log.contains("[Error]", true)
                    val isWarn = log.contains("WARN", true)

                    val textColor = when {
                        isError -> Color(0xFFFF5252)
                        isWarn -> Color(0xFFFFD740)
                        log.contains("[App]") -> Color(0xFF00E5FF)
                        log.contains("[Core]") -> Color(0xFFA0A0A0)
                        else -> Color.LightGray
                    }

                    Text(
                        text = log,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = textColor,
                        lineHeight = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isError) Modifier.background(Color(0x22FF5252), androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(4.dp) else Modifier)
                    )
                }
            }
        }
    }
}

// ==================== 全局开源声明组件 ====================
@Composable
fun OpenSourceLink(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("本项目已在 github 上开源 ", color = Color.Gray, fontSize = 12.sp)
        Text(
            "打开",
            color = NeonGreen,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/xiaod666/Mcilm-link-Android"))
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, "未安装浏览器，无法打开链接", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}