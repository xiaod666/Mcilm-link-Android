package wasai.link.mcilm.top.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wasai.link.mcilm.top.ui.theme.DarkSurface
import wasai.link.mcilm.top.ui.theme.NeonGreen
import wasai.link.mcilm.top.viewmodel.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }  // 0=提交 1=我的反馈
    var category by rememberSaveable { mutableStateOf("bug") }
    var content by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.fetchFeedbackList() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("意见反馈", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = NeonGreen,
                divider = { HorizontalDivider(color = Color.DarkGray.copy(alpha = 0.5f)) }
            ) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("提交反馈", fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal) }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("我的反馈", fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal) }
                )
            }

            if (tab == 0) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column {
                                Text("反馈类型", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = category == "bug",
                                        onClick = { category = "bug" },
                                        label = { Text("问题反馈") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = NeonGreen.copy(alpha = 0.2f),
                                            selectedLabelColor = NeonGreen
                                        )
                                    )
                                    FilterChip(
                                        selected = category == "suggest",
                                        onClick = { category = "suggest" },
                                        label = { Text("功能建议") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = NeonGreen.copy(alpha = 0.2f),
                                            selectedLabelColor = NeonGreen
                                        )
                                    )
                                    FilterChip(
                                        selected = category == "other",
                                        onClick = { category = "other" },
                                        label = { Text("其他") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = NeonGreen.copy(alpha = 0.2f),
                                            selectedLabelColor = NeonGreen
                                        )
                                    )
                                }
                            }

                            OutlinedTextField(
                                value = content,
                                onValueChange = { if (it.length <= 1000) content = it },
                                label = { Text("反馈内容（5~1000 字）") },
                                minLines = 5,
                                modifier = Modifier.fillMaxWidth(),
                                isError = content.isNotEmpty() && content.trim().length < 5,
                                supportingText = { Text("${content.length}/1000", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonGreen,
                                    cursorColor = NeonGreen,
                                    focusedLabelColor = NeonGreen
                                )
                            )

                            OutlinedTextField(
                                value = contact,
                                onValueChange = { if (it.length <= 64) contact = it },
                                label = { Text("联系方式（选填）") },
                                placeholder = { Text("QQ / 邮箱等") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonGreen,
                                    cursorColor = NeonGreen,
                                    focusedLabelColor = NeonGreen
                                )
                            )
                        }
                    }

                    Button(
                        onClick = {
                            vm.submitFeedback(category, content, contact) { success ->
                                // 修复 P0-4：只有成功才清空
                                if (success) {
                                    content = ""
                                    contact = ""
                                }
                            }
                        },
                        enabled = content.trim().length in 5..1000 && !state.isLoading,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonGreen,
                            contentColor = Color.Black,
                            disabledContainerColor = NeonGreen.copy(alpha = 0.15f),
                            disabledContentColor = Color.LightGray.copy(alpha = 0.5f)
                        )
                    ) {
                        Text("提 交", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                if (state.feedbackList.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无反馈记录", color = Color.Gray)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        items(state.feedbackList) { fb ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val (icon, color, label) = when (fb.category) {
                                                "bug" -> Triple(Icons.Default.Warning, Color(0xFFFF5252), "问题")
                                                "suggest" -> Triple(Icons.Default.Star, Color(0xFFFFD740), "建议")
                                                else -> Triple(Icons.Default.Info, Color(0xFF448AFF), "其他")
                                            }
                                            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("${label}反馈", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    if (fb.status == 1) NeonGreen.copy(alpha = 0.2f) else Color.DarkGray,
                                                    RoundedCornerShape(4.dp)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                if (fb.status == 1) "已处理" else "待处理",
                                                color = if (fb.status == 1) NeonGreen else Color.Gray,
                                                fontSize = 11.sp
                                            )
                                        }
                                    }

                                    Spacer(Modifier.height(10.dp))
                                    Text(fb.content, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)

                                    if (!fb.reply.isNullOrBlank()) {
                                        Spacer(Modifier.height(12.dp))
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(Modifier.padding(10.dp)) {
                                                Text("官方回复", color = NeonGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                Spacer(Modifier.height(4.dp))
                                                Text(fb.reply, color = Color.LightGray, fontSize = 13.sp, lineHeight = 18.sp)
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(10.dp))
                                    Text(fb.createdAt, color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}