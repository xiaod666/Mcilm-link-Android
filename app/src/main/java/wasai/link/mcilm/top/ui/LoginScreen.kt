package wasai.link.mcilm.top.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wasai.link.mcilm.top.ui.theme.DarkSurface
import wasai.link.mcilm.top.ui.theme.NeonGreen
import wasai.link.mcilm.top.viewmodel.AuthState
import wasai.link.mcilm.top.viewmodel.*

@Composable
fun LoginScreen(vm: MainViewModel, onLoginSuccess: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var showTraditionalLogin by rememberSaveable { mutableStateOf(false) }
    var prefilledAccount by rememberSaveable { mutableStateOf("") }

    var rememberedAccounts by remember { mutableStateOf(vm.tokenManager.getRememberedAccounts()) }

    LaunchedEffect(state.authState) {
        if (state.authState == AuthState.Authenticated) {
            focusManager.clearFocus()
            onLoginSuccess()
        }
    }

    AnimatedContent(
        targetState = if (rememberedAccounts.isNotEmpty() && !showTraditionalLogin) "selection" else "traditional",
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "LoginScreenTransition"
    ) { target ->
        if (target == "selection") {
            AccountSelectionView(
                vm = vm,
                accounts = rememberedAccounts,
                onSwitchToTraditional = {
                    prefilledAccount = ""
                    showTraditionalLogin = true
                },
                onDeleteAccount = { acc ->
                    vm.removeRememberedAccount(acc)
                    rememberedAccounts = vm.tokenManager.getRememberedAccounts()
                    Toast.makeText(context, "已移除账号记录", Toast.LENGTH_SHORT).show()
                },
                onForcePassword = { acc ->
                    prefilledAccount = acc
                    showTraditionalLogin = true
                    Toast.makeText(context, "安全验证：请输入密码", Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            TraditionalLoginView(
                vm = vm,
                state = state,
                context = context,
                initialAccount = prefilledAccount,
                onBackToSelection = if (rememberedAccounts.isNotEmpty()) {
                    { showTraditionalLogin = false }
                } else null
            )
        }
    }
}

// ==================== 视图一：独立的账号选择（多账号列表） ====================
@Composable
private fun AccountSelectionView(
    vm: MainViewModel,
    accounts: List<wasai.link.mcilm.top.data.TokenManager.RememberedAccount>,
    onSwitchToTraditional: () -> Unit,
    onDeleteAccount: (String) -> Unit,
    onForcePassword: (String) -> Unit
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var loggingInAccount by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            loggingInAccount = null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(64.dp))
        Text("Mcilm 联机助手", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("选择账号以继续", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)

        Spacer(Modifier.height(32.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(accounts, key = { it.account }) { cred ->
                val now = System.currentTimeMillis()
                val gracePeriod = 3L * 24 * 60 * 60 * 1000L
                val checkInterval = 24L * 60 * 60 * 1000L

                // 如果密码为空、过期时间<=0、或超过免密期限，强制输入密码
                val isForcePassword = cred.password.isBlank() || cred.expireAt <= 0 ||
                        ((now > cred.lastManualLoginAt + gracePeriod) && (now - cred.lastAutoLoginAt >= checkInterval))

                val isLoadingThis = loggingInAccount == cred.account

                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = BorderStroke(1.dp, if (isForcePassword) Color.DarkGray else NeonGreen.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val initial = cred.account.firstOrNull()?.uppercase() ?: "?"
                        Box(
                            modifier = Modifier.size(48.dp).background(NeonGreen.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(initial, color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }

                        Spacer(Modifier.width(16.dp))

                        Column(
                            modifier = Modifier.weight(1f).clickable(enabled = loggingInAccount == null && !state.isLoading) {
                                if (isForcePassword) {
                                    onForcePassword(cred.account)
                                } else {
                                    loggingInAccount = cred.account
                                    vm.loginWithSpecificAccount(cred.account)
                                }
                            }
                        ) {
                            Text(cred.account, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(4.dp))
                            Surface(
                                color = if (isForcePassword) Color(0x33FF5252) else NeonGreen.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (isForcePassword) "需验证密码" else "免密登录",
                                    color = if (isForcePassword) MaterialTheme.colorScheme.error else NeonGreen,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (isLoadingThis) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = NeonGreen, strokeWidth = 2.dp)
                        } else {
                            IconButton(
                                onClick = { onDeleteAccount(cred.account) },
                                enabled = loggingInAccount == null && !state.isLoading
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "删除账号", tint = Color.Gray, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onSwitchToTraditional,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen),
                    border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.3f)),
                    enabled = loggingInAccount == null && !state.isLoading
                ) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("使用其他账号登录", fontSize = 15.sp)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ==================== 视图二：传统的输入框登录 ====================
@Composable
private fun TraditionalLoginView(
    vm: MainViewModel,
    state: UiState,
    context: android.content.Context,
    initialAccount: String = "",
    onBackToSelection: (() -> Unit)? = null
) {
    var account by rememberSaveable { mutableStateOf(initialAccount) }
    var password by remember { mutableStateOf("") }
    var agreePrivacy by rememberSaveable { mutableStateOf(false) }
    var rememberMe by rememberSaveable { mutableStateOf(true) }
    var lastToastTime by remember { mutableStateOf(0L) }

    val passwordFocusRequester = FocusRequester()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        if (initialAccount.isNotBlank()) {
            kotlinx.coroutines.delay(300)
            passwordFocusRequester.requestFocus()
        }
    }

    val checkLimit: (String, Int, String) -> String = { text, max, name ->
        if (text.length > max) {
            val now = System.currentTimeMillis()
            if (now - lastToastTime > 1500) {
                Toast.makeText(context, "$name 最多输入 $max 个字符，已截断", Toast.LENGTH_SHORT).show()
                lastToastTime = now
            }
            text.take(max)
        } else text
    }

    val submitLogin = {
        if (!agreePrivacy) {
            Toast.makeText(context, "请先阅读并同意用户协议与隐私政策", Toast.LENGTH_SHORT).show()
        } else {
            val trimmedAccount = account.trim()
            if (!state.isLoading) {
                focusManager.clearFocus()
                vm.login(trimmedAccount, password, rememberMe)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(64.dp))

        if (onBackToSelection != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = onBackToSelection, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.LightGray)
                }
            }
        } else {
            Spacer(Modifier.height(48.dp))
        }

        Text("Mcilm 联机助手", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = account,
            onValueChange = { account = checkLimit(it.replace("\n", "").replace("\r", ""), 50, "账号") },
            label = { Text("账号") },
            singleLine = true,
            enabled = !state.isLoading,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonGreen,
                unfocusedBorderColor = Color.DarkGray,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() }),
            trailingIcon = { ClearIcon(account) { account = "" } },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = checkLimit(it.replace("\n", "").replace("\r", ""), 100, "密码") },
            label = { Text("密码") },
            singleLine = true,
            enabled = !state.isLoading,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonGreen,
                unfocusedBorderColor = Color.DarkGray,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent
            ),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submitLogin() }),
            trailingIcon = { ClearIcon(password) { password = "" } },
            modifier = Modifier.fillMaxWidth().focusRequester(passwordFocusRequester)
        )

        if (state.errorMsg != null) {
            Spacer(Modifier.height(8.dp))
            Text(state.errorMsg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { rememberMe = !rememberMe }
        ) {
            Checkbox(
                checked = rememberMe,
                onCheckedChange = { rememberMe = it },
                enabled = !state.isLoading,
                colors = CheckboxDefaults.colors(
                    checkedColor = NeonGreen,
                    uncheckedColor = Color.Gray,
                    checkmarkColor = Color.Black
                )
            )
            Text(
                "记住密码（3天内免密，10天有效）",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = agreePrivacy,
                onCheckedChange = { agreePrivacy = it },
                enabled = !state.isLoading,
                colors = CheckboxDefaults.colors(
                    checkedColor = NeonGreen,
                    uncheckedColor = Color.Gray,
                    checkmarkColor = Color.Black
                )
            )
            val annotated = buildAnnotatedString {
                append("我已阅读并同意")
                pushStringAnnotation(tag = "privacy", annotation = "privacy")
                withStyle(SpanStyle(color = NeonGreen)) { append("《隐私政策》") }
                pop()
                append("和")
                pushStringAnnotation(tag = "terms", annotation = "terms")
                withStyle(SpanStyle(color = NeonGreen)) { append("《用户协议》") }
                pop()
            }
            ClickableText(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium.copy(color = Color.LightGray),
                onClick = { offset ->
                    annotated.getStringAnnotations(tag = "privacy", start = offset, end = offset).firstOrNull()?.let {
                        Toast.makeText(context, "请前往官网 link.mcilm.top 查看《隐私政策》", Toast.LENGTH_SHORT).show()
                    }
                    annotated.getStringAnnotations(tag = "terms", start = offset, end = offset).firstOrNull()?.let {
                        Toast.makeText(context, "请前往官网 link.mcilm.top 查看《用户协议》", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick = { submitLogin() },
            enabled = !state.isLoading && agreePrivacy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = NeonGreen,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = Color.Gray
            ),
            border = BorderStroke(1.dp, if (!state.isLoading && agreePrivacy) NeonGreen else Color.DarkGray)
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = NeonGreen, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("登录中...", fontWeight = FontWeight.Bold, color = NeonGreen)
            } else {
                Text("登录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}