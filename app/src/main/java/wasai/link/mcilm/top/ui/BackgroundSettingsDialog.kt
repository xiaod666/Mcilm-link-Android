package wasai.link.mcilm.top.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun BackgroundSettingsDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要允许后台运行") },
        text = {
            Text(
                "为保证您在锁屏或切换到其他应用后，联机/语音会话不被系统中断，请允许本应用在后台运行。\n\n" +
                        "1. 忽略电池优化（防断网）\n" +
                        "2. 允许自启动（防强杀）\n\n" +
                        "设置仅在联机期间生效，不会额外耗电。"
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                openBatteryOptimizationSettings(context)
                // 【P2-4 修复】延迟 600ms 尝试拉起自启动设置
                Handler(Looper.getMainLooper()).postDelayed({
                    openAutoStartSettings(context)
                }, 600)
            }) { Text("去设置") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("暂不开启") }
        }
    )
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    return true
}

@SuppressLint("BatteryLife")
fun openBatteryOptimizationSettings(context: Context) {
    val pkg = context.packageName
    val candidates = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$pkg")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg"))
    )
    for (intent in candidates) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) { }
    }
}

fun openAutoStartSettings(context: Context) {
    val pkg = context.packageName
    val manufacturer = Build.MANUFACTURER.lowercase()

    val intent = when {
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
        }
        manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
            Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
        }
        manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
            Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
        }
        manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
        }
        else -> null
    }

    try {
        if (intent != null) {
            context.startActivity(intent)
        } else {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg")))
        }
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg")))
        } catch (_: Exception) { }
    }
}