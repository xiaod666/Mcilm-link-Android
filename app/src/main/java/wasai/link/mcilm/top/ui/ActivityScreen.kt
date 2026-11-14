package wasai.link.mcilm.top.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
fun ActivityScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.fetchActivities() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("活动中心") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        if (state.activities.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("暂无进行中的活动", color = Color.Gray)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(state.activities) { act ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(act.title, color = Color.White, fontWeight = FontWeight.Bold)
                                Text(
                                    "奖励 ${act.rewardSec / 60} 分钟",
                                    color = NeonGreen, fontSize = 13.sp
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("${act.startAt} ~ ${act.endAt}", color = Color.Gray, fontSize = 12.sp)
                            Spacer(Modifier.height(8.dp))
                            if (act.content.isNotBlank()) {
                                HtmlContent(act.content, Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 200.dp))
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { vm.claimActivity(act.id) },
                                enabled = !act.claimed,
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(if (act.claimed) "已领取" else "一键领取")
                            }
                        }
                    }
                }
            }
        }
    }
}