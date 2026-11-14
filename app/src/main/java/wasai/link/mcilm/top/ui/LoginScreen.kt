package wasai.link.mcilm.top.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
    var account by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var agreePrivacy by rememberSaveable { mutableStateOf(false) }

    val passwordFocusRequester = FocusRequester()
    val focusManager = LocalFocusManager.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var lastToastTime by remember { mutableStateOf(0L) }

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

    LaunchedEffect(state.authState) {
        if (state.authState == AuthState.Authenticated) {
            focusManager.clearFocus()
            onLoginSuccess()
        }
    }

    val submitLogin = {
        if (!agreePrivacy) {
            Toast.makeText(context, "请先阅读并同意用户协议与隐私政策", Toast.LENGTH_SHORT).show()
        } else {
            val trimmedAccount = account.trim()
            if (!state.isLoading) {
                focusManager.clearFocus()
                vm.login(trimmedAccount, password)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Mcilm 联机助手", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(48.dp))

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
            Text(state.errorMsg!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(16.dp))

        // [新增] 隐私协议勾选（合规硬要求）
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(
                checked = agreePrivacy,
                onCheckedChange = { agreePrivacy = it },
                enabled = !state.isLoading
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
                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp),
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
    }
}