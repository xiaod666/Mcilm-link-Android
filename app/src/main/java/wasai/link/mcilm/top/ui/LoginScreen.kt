package wasai.link.mcilm.top.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    if (rememberedAccounts.isNotEmpty() && !showTraditionalLogin) {
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
            },
            onForcePassword = { acc ->
                prefilledAccount = acc
                showTraditionalLogin = true
                Toast.makeText(context, "免密已失效，请重新输入密码", Toast.LENGTH_SHORT).show()
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

    Column(
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Mcilm 联机助手", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(32.dp))
        Text("选择账号登录", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
        Spacer(Modifier.height(16.dp))

        accounts.forEach { cred ->
            // 核心逻辑：计算是否免密
            val now = System.currentTimeMillis()
            val gracePeriod = 3L * 24 * 60 * 60 * 1000L // 3 天免密期
            val checkInterval = 24L * 60 * 60 * 1000L // 24 小时（隔天验证）

            // 超过 3 天免密期，且距离上次免密登录超过 24 小时，则必须输入密码
            val isForcePassword = (now > cred.lastManualLoginAt + gracePeriod) && (now - cred.lastAutoLoginAt >= checkInterval)

            Card(
                colors = CardDefaults.cardColors(containerColor = NeonGreen.copy(alpha = 0.1f)),
                border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AccountCircle, contentDescription = null, tint = NeonGreen, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f).clickable {
                        if (!state.isLoading) {
                            if (isForcePassword) {
                                onForcePassword(cred.account)
                            } else {
                                vm.loginWithSpecificAccount(cred.account)
                            }
                        }
                    }) {
                        Text(cred.account, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            if (isForcePassword) "免密已失效，需验证" else "免密登录",
                            color = if (isForcePassword) MaterialTheme.colorScheme.error else Color.Gray,
                            fontSize = 12.sp
                        )
                    }

                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = NeonGreen, strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { onDeleteAccount(cred.account) }) {
                            Icon(Icons.Default.Close, contentDescription = "删除账号", tint = Color.DarkGray, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onSwitchToTraditional,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen),
            border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.5f))
        ) {
            Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("使用其他账号登录")
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
    // 因为是从强制验证跳过来的，默认勾选方便续期
    var rememberMe by rememberSaveable { mutableStateOf(true) }
    var lastToastTime by remember { mutableStateOf(0L) }

    val passwordFocusRequester = FocusRequester()
    val focusManager = LocalFocusManager.current

    val checkLimit: (String, Int, String) -> String = { text, max, name ->
        if (text.length > max) {
            val now = System.currentTimeMillis()
            if (now - lastToastTime > 1500) {
                Toast.makeText(context, "$name 最多输入 $max 个字符，多出部分已截断", Toast.LENGTH_SHORT).show()
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
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Mcilm 联机助手", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = account,
            onValueChange = { account = checkLimit(it.replace("\n", "").replace("\r", ""), 50, "账号") },
            label = { Text("账号") }, singleLine = true,
            enabled = !state.isLoading,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() }),
            trailingIcon = { ClearIcon(account) { account = "" } },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = checkLimit(it.replace("\n", "").replace("\r", ""), 100, "密码") },
            label = { Text("密码") }, singleLine = true,
            enabled = !state.isLoading,
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

        Spacer(Modifier.height(12.dp))

        // 记住密码勾选框
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
                "记住账号密码（10天内有效）",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray
            )
        }

        // 隐私协议勾选框
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

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { submitLogin() },
            enabled = !state.isLoading && agreePrivacy,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("登录中...")
            } else {
                Text("登录", style = MaterialTheme.typography.titleMedium)
            }
        }

        if (onBackToSelection != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onBackToSelection, enabled = !state.isLoading) {
                Text("返回账号选择", color = NeonGreen, fontSize = 14.sp)
            }
        }
    }
}