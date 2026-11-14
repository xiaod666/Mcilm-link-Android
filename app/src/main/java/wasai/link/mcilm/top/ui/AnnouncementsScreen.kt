package wasai.link.mcilm.top.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wasai.link.mcilm.top.network.Announcement
import wasai.link.mcilm.top.ui.theme.DarkSurface
import wasai.link.mcilm.top.ui.theme.NeonGreen
import wasai.link.mcilm.top.viewmodel.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var selectedAnnouncement by remember { mutableStateOf<Announcement?>(null) }

    LaunchedEffect(Unit) { vm.fetchAnnouncements() }

    // 拦截系统返回键，如果在详情页则返回列表
    BackHandler(enabled = selectedAnnouncement != null) {
        selectedAnnouncement = null
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 600.dp

        if (isWideScreen) {
            // 平板/折叠屏：左右分栏
            Row(Modifier.fillMaxSize()) {
                // 左侧列表
                Column(Modifier.width(360.dp).fillMaxHeight().background(MaterialTheme.colorScheme.background)) {
                    AnnouncementTopBar(title = "收件箱", onBack = onBack)
                    HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.5f))
                    AnnouncementList(
                        list = state.announcements,
                        selectedId = selectedAnnouncement?.id,
                        onSelect = { ann ->
                            selectedAnnouncement = ann
                            vm.markAnnouncementRead(ann.id)
                        }
                    )
                }
                VerticalDivider(color = Color.DarkGray.copy(alpha = 0.5f))
                // 右侧详情
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (selectedAnnouncement != null) {
                        AnnouncementDetailContent(selectedAnnouncement!!)
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("选择一封公告查看详情", color = Color.Gray)
                        }
                    }
                }
            }
        } else {
            // 手机竖屏：单页切换
            if (selectedAnnouncement == null) {
                Column(Modifier.fillMaxSize()) {
                    AnnouncementTopBar(title = "公告收件箱", onBack = onBack)
                    HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.5f))
                    AnnouncementList(
                        list = state.announcements,
                        selectedId = null,
                        onSelect = { ann ->
                            selectedAnnouncement = ann
                            vm.markAnnouncementRead(ann.id)
                        }
                    )
                }
            } else {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("公告详情") },
                            navigationIcon = {
                                IconButton(onClick = { selectedAnnouncement = null }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回列表")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                        )
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        AnnouncementDetailContent(selectedAnnouncement!!)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnnouncementTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
    )
}

@Composable
private fun AnnouncementList(
    list: List<Announcement>,
    selectedId: Int?,
    onSelect: (Announcement) -> Unit
) {
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无公告", color = Color.Gray)
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { item ->
            val isSelected = item.id == selectedId
            val bgColor = if (isSelected) NeonGreen.copy(alpha = 0.1f) else Color.Transparent

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor)
                    .clickable { onSelect(item) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.Top
            ) {
                // 未读圆点 / 图标
                Box(
                    modifier = Modifier.padding(top = 4.dp).size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (!item.read) {
                        Box(Modifier.size(8.dp).background(NeonGreen, CircleShape))
                    } else {
                        Icon(Icons.Default.Drafts, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "系统通知",
                            color = if (!item.read) Color.White else Color.Gray,
                            fontSize = 13.sp,
                            fontWeight = if (!item.read) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = formatMailTime(item.createdAt),
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.title,
                        color = if (!item.read) Color.White else Color.LightGray,
                        fontSize = 15.sp,
                        fontWeight = if (!item.read) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stripHtml(item.content),
                        color = Color.Gray,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.3f), modifier = Modifier.padding(start = 52.dp))
        }
    }
}

@Composable
private fun AnnouncementDetailContent(item: Announcement) {
    Column(
        Modifier.fillMaxSize().padding(16.dp)
    ) {
        Text(
            text = item.title,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 28.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Email, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("系统通知  ·  ${item.createdAt}", color = Color.Gray, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.5f))
        Spacer(Modifier.height(16.dp))

        // WebView 填满剩余空间，内部自己滚动，防止嵌套滚动冲突
        HtmlContent(html = item.content, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

// 提取纯文本作邮件预览
private fun stripHtml(html: String): String {
    return html.replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .trim()
}

// 简化时间格式，如 "2026-10-13 20:00:00" -> "10-13 20:00"
private fun formatMailTime(timeStr: String): String {
    return if (timeStr.length >= 16) {
        timeStr.substring(5, 16)
    } else {
        timeStr
    }
}