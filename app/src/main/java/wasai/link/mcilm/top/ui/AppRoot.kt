package wasai.link.mcilm.top.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wasai.link.mcilm.top.link.MciLmNative
import wasai.link.mcilm.top.service.LinkKeepAliveService
import wasai.link.mcilm.top.ui.theme.McilmTheme
import wasai.link.mcilm.top.ui.theme.NeonGreen
import wasai.link.mcilm.top.viewmodel.AuthState
import wasai.link.mcilm.top.viewmodel.*
import wasai.link.mcilm.top.viewmodel.UiEvent

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsState()
    // 【修复】更新状态提升到全局，任何页面都能弹出更新对话框
    val updateState by vm.updateState.collectAsState()
    val updateDialogVisible by vm.updateDialogVisible.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()

    // 内核就绪状态放在 ViewModel 里判断：Activity 重建（旋转/深色模式切换）时不必再走一遍
    // 动态库下载校验与联网检查，也就不会闪一下启动 Gate。
    var isLibReady by remember { mutableStateOf(vm.libraryReady) }
    var fatalError by remember { mutableStateOf<String?>(null) }

    // 全局保活弹窗控制
    var showBackgroundDialog by remember { mutableStateOf(false) }

    // 通知权限申请器
    val requestNotificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // ==================== 监听登录状态：只负责启停服务，不负责弹窗 ====================
    LaunchedEffect(state.authState) {
        if (state.authState == AuthState.Authenticated) {
            // 启动前台保活服务
            val serviceIntent = Intent(context, LinkKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            // 申请通知权限
            if (Build.VERSION.SDK_INT >= 33) {
                val permission = Manifest.permission.POST_NOTIFICATIONS
                if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    requestNotificationPermissionLauncher.launch(permission)
                }
            }
        } else if (state.authState == AuthState.Unauthenticated) {
            // 退出登录时停止保活服务
            context.stopService(Intent(context, LinkKeepAliveService::class.java))
        }
    }

    // 【修复】唤醒锁跟随「是否在房间中」：进房才真正持锁，退房立即释放，
    // 避免"仅登录但没开房"时一直持有 PARTIAL_WAKE_LOCK 白白耗电。
    LaunchedEffect(state.authState, state.roomId) {
        if (state.authState != AuthState.Authenticated) return@LaunchedEffect
        val lockIntent = Intent(context, LinkKeepAliveService::class.java).apply {
            action = LinkKeepAliveService.ACTION_SET_ACTIVE
            putExtra(LinkKeepAliveService.EXTRA_ACTIVE, state.roomId != null)
        }
        // 后台启动限制（Android 8+/12+）下可能抛异常，唤醒锁属于可选增强，失败不影响主流程
        runCatching { context.startService(lockIntent) }
    }

    LaunchedEffect(Unit) {
        // 旋转等重建场景：ViewModel 里已标记内核就绪，直接放行，避免重复 initialize
        if (vm.libraryReady) {
            isLibReady = true
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            MciLmNative.initFileLogging(context)
            val result = MciLmNative.initialize(context)
            if (result.fatalError != null) {
                fatalError = result.fatalError
            } else {
                if (!result.warningMsg.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, result.warningMsg, Toast.LENGTH_LONG).show()
                    }
                }
                isLibReady = true
                vm.onLibraryReady()
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.uiEvent.collect { event ->
            when (event) {
                is UiEvent.ShowSnackbar -> {
                    launch {
                        snackbarHostState.showSnackbar(
                            message = event.message,
                            duration = if (event.isLong) SnackbarDuration.Long else SnackbarDuration.Short
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(state.authState, isLibReady) {
        if (isLibReady && state.authState == AuthState.Unauthenticated) {
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            if (currentRoute != "login") {
                navController.navigate("login") {
                    popUpTo(navController.graph.startDestinationId) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    // 【修复】访客加入成功后自动进入房间界面。
    // 此前 "room" 路由虽然注册了 HostScreen，但全工程没有任何 navigate 调用，
    // 导致访客加房成功后仍停留在 JoinScreen，聊天 / 文件 / 成员 / 停止房间全部不可达。
    LaunchedEffect(currentRoute, state.roomId, state.isJoining, state.isHosting, state.authState) {
        val route = currentRoute
        // 只在"已登录 且 从首页/加入页发起"时跳转：
        // 否则会话中途 token 失效跳到 login 后，会被这条逻辑重新拉回房间（栈变成 login→room）。
        if (state.authState != AuthState.Authenticated) return@LaunchedEffect
        if (route != "home" && route != "join") return@LaunchedEffect
        if (state.roomId == null || state.isHosting || !state.isJoining) return@LaunchedEffect
        navController.navigate("room") {
            launchSingleTop = true
            // 从加入页进来的话，把它从返回栈里替换掉，避免退出房间后回到空的加入页
            if (route == "join") popUpTo("join") { inclusive = true }
        }
    }

    var wasInSession by remember { mutableStateOf(false) }
    LaunchedEffect(currentRoute, state.roomId, state.isHosting, state.isJoining) {
        val inRoom = currentRoute == "host" || currentRoute == "join" || currentRoute == "room"
        if (!inRoom) { wasInSession = false; return@LaunchedEffect }
        val inSession = state.roomId != null || state.isHosting || state.isJoining
        if (inSession) wasInSession = true
        else if (wasInSession) {
            navController.popBackStack("home", inclusive = false)
            wasInSession = false
        }
    }

    McilmTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            modifier = Modifier.fillMaxSize()
        ) { paddingValues ->
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                if (!isLibReady) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (fatalError == null) {
                            CircularProgressIndicator(modifier = Modifier.size(48.dp), color = NeonGreen)
                            Spacer(Modifier.height(16.dp))
                            Text("正在校验内核与检查更新...", color = MaterialTheme.colorScheme.onBackground)
                        } else {
                            Text("启动失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(16.dp))
                            Text(fatalError!!, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    // 【修复】加上 currentRoute != "login" 的判断，防止在登录页弹全局 Loading 弹窗
                    if (state.isLoading && currentRoute != "login") {
                        // 【修复】全局 Loading 此前完全不可取消（onDismissRequest = {}、confirmButton = {}），
                        // 任何一条忘记复位 isLoading 的路径都会把用户永久锁在弹窗里。
                        // 现在超时 12 秒后给出「取消操作」出口，正常耗时内行为不变。
                        var allowCancelLoading by remember { mutableStateOf(false) }
                        LaunchedEffect(state.isLoading, state.loadingMsg) {
                            allowCancelLoading = false
                            if (state.isLoading) {
                                delay(12_000L)
                                allowCancelLoading = true
                            }
                        }
                        AlertDialog(
                            onDismissRequest = { if (allowCancelLoading) vm.cancelLoading() },
                            title = { Text("请稍候") },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    Spacer(Modifier.width(16.dp))
                                    Text(state.loadingMsg)
                                }
                            },
                            confirmButton = {
                                if (allowCancelLoading) {
                                    TextButton(onClick = { vm.cancelLoading() }) {
                                        Text("取消操作", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        )
                    }

                    if (state.errorMsg != null) {
                        AlertDialog(
                            onDismissRequest = { vm.dismissError() },
                            title = { Text("提示") },
                            text = { Text(state.errorMsg!!) },
                            confirmButton = { TextButton(onClick = { vm.retryLastAction() }) { Text("重试") } },
                            dismissButton = { TextButton(onClick = { vm.dismissError() }) { Text("取消") } }
                        )
                    }

                    NavHost(
                        navController = navController,
                        startDestination = if (state.authState == AuthState.Authenticated) "home" else "login"
                    ) {
                        composable("login") {
                            LoginScreen(vm, onLoginSuccess = {
                                navController.navigate("home") {
                                    popUpTo("login") { inclusive = true }
                                    launchSingleTop = true
                                }

                                // 【核心修改】只有手动点击登录成功后，才检查并弹窗！
                                val prefs = context.getSharedPreferences("mci_settings", Context.MODE_PRIVATE)
                                if (!prefs.getBoolean("battery_opt_dismissed", false)) {
                                    showBackgroundDialog = true
                                }
                            })
                        }
                        composable("home") {
                            HomeScreen(
                                vm,
                                onNavigateHost = { navController.navigate("host") },
                                onNavigateJoin = { navController.navigate("join") },
                                onNavigateSettings = { navController.navigate("settings") },
                                onNavigateAnnouncements = { navController.navigate("announcements") },
                                onNavigateActivity = { navController.navigate("activity") }
                            )
                        }
                        composable("host") {
                            HostScreen(vm, onBack = { navController.popBackStack() })
                        }
                        composable("join") {
                            JoinScreen(vm, onBack = { navController.popBackStack() })
                        }
                        composable("room") {
                            HostScreen(vm, onBack = { navController.popBackStack() })
                        }
                        composable("settings") {
                            SettingsScreen(
                                vm,
                                onBack = { navController.popBackStack() },
                                onNavigateAnnouncements = { navController.navigate("announcements") },
                                onNavigateActivity = { navController.navigate("activity") },
                                onNavigateInvite = { navController.navigate("invite") },
                                onNavigateFeedback = { navController.navigate("feedback") }
                            )
                        }
                        composable("announcements") {
                            AnnouncementsScreen(vm, onBack = { navController.popBackStack() })
                        }
                        composable("activity") {
                            ActivityScreen(vm, onBack = { navController.popBackStack() })
                        }
                        composable("invite") {
                            InviteScreen(vm, onBack = { navController.popBackStack() })
                        }
                        composable("feedback") {
                            FeedbackScreen(vm, onBack = { navController.popBackStack() })
                        }
                    }

                    // 【修复】更新对话框从 HomeScreen 内嵌提升为全局：
                    // 设置页“检查更新”发现新版本后即可直接下载/安装，不再只能回首页点红点。
                    if (updateDialogVisible) {
                        UpdateDialog(
                            state = updateState,
                            onDismiss = { vm.dismissUpdateDialog() },
                            onDownload = { vm.startDownloadUpdate() },
                            onInstall = { vm.installDownloadedApk() },
                            onCancelDownload = { vm.cancelDownloadUpdate() }
                        )
                    }
                }

                // ==================== 全局保活引导弹窗 ====================
                if (showBackgroundDialog) {
                    BackgroundSettingsDialog(
                        onDismiss = {
                            showBackgroundDialog = false
                            // 点“暂不开启”：记住状态，以后不弹
                            context.getSharedPreferences("mci_settings", Context.MODE_PRIVATE)
                                .edit().putBoolean("battery_opt_dismissed", true).apply()
                        },
                        onConfirm = {
                            showBackgroundDialog = false
                            // 点“去设置”：记住状态，以后不弹
                            context.getSharedPreferences("mci_settings", Context.MODE_PRIVATE)
                                .edit().putBoolean("battery_opt_dismissed", true).apply()
                        }
                    )
                }
            }
        }
    }
}