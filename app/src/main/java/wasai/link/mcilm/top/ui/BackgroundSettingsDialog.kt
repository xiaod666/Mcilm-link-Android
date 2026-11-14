package wasai.link.mcilm.top.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
                // 优先请求忽略电池优化（会弹出系统标准授权框）
                openBatteryOptimizationSettings(context)
                // 为了兼容国产 ROM，可以紧接着尝试打开自启动页（部分 ROM 如小米不支持连续跳转，可分开做）
                // 这里只做基础跳转，具体体验需根据用户点击行为调整
            }) { Text("去设置") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("暂不开启") }
        }
    )
}

/**
 * 判断应用是否已加入电池优化白名单
 */
fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    return true
}

/**
 * 逐级降级打开电池优化设置，避免部分 ROM 不支持导致崩溃
 */
@SuppressLint("BatteryLife")
fun openBatteryOptimizationSettings(context: Context) {
    val pkg = context.packageName
    val candidates = listOf(
        // 1. 直接请求忽略电池优化（标准弹窗，部分国产 ROM 可能拦截）
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$pkg")),
        // 2. 电池优化列表页（让用户自己找 App 并设置为“不优化”）
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        // 3. 应用详情页（保底，所有 Android 都支持）
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$pkg"))
    )

    for (intent in candidates) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // 忽略异常，继续尝试下一个 Intent
        }
    }
}

/**
 * 尝试跳转到国产 ROM 的自启动管理页
 * 如果找不到，则回退到应用详情页
 */
fun openAutoStartSettings(context: Context) {
    val pkg = context.packageName
    val manufacturer = Build.MANUFACTURER.lowercase()

    val intent = when {
        // 小米 / Redmi
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            )
        }
        // 华为 / 荣耀
        manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
            Intent().setComponent(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            )
        }
        // OPPO / Realme / 一加
        manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
            Intent().setComponent(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            )
        }
        // vivo / iQOO
        manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
            Intent().setComponent(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            )
        }
        // 其它（兜底）
        else -> null
    }

    try {
        if (intent != null) {
            context.startActivity(intent)
        } else {
            // 如果没有匹配的 ROM，打开应用详情页
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$pkg"))
            )
        }
    } catch (_: Exception) {
        // 虽然匹配了厂商，但组件被精简或权限不足，使用应用详情页兜底
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$pkg"))
            )
        } catch (_: Exception) {
            // 彻底失败，忽略（不会崩溃）
        }
    }
}