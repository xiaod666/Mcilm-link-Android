package wasai.link.mcilm.top.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// ===== 基础色板 =====
val NeonGreen = Color(0xFF00FF88)
val CyanAccent = Color(0xFF00E5FF)
val AmberAccent = Color(0xFFFFC400)
val DarkBg = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E2E)
val DarkSurfaceVariant = Color(0xFF2A2A3C)

/**
 * 深色配色。
 *
 * 【统一】此前只声明了 8 个槽位，其余槽位走 Material3 默认值（偏紫的
 * primaryContainer / secondaryContainer / surfaceVariant 等），
 * 会让 FilterChip、输入框容器、分隔线在霓虹绿主题里露出紫色。
 * 这里把容器色、描边色与反色（Snackbar 用）一并补齐。
 */
private val DarkColorScheme = darkColorScheme(
    primary = NeonGreen,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF06331D),
    onPrimaryContainer = Color(0xFF7CFFC0),

    secondary = CyanAccent,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF06323A),
    onSecondaryContainer = Color(0xFF8BF2FF),

    tertiary = AmberAccent,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF3A2E00),
    onTertiaryContainer = Color(0xFFFFE08A),

    background = DarkBg,
    onBackground = Color.White,

    surface = DarkSurface,
    onSurface = Color.White,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB9B9C9),
    // 用中性色做 surfaceTint：Material3 的 AlertDialog / 底部弹层是按 surface + tint 叠加计算的，
    // 之前把 tint 设成霓虹绿会让所有弹窗泛一层绿光，看起来像"脏色"。
    surfaceTint = DarkSurface,

    // 高层级表面：统一成同一套深灰，避免默认值里偏紫的容器色漏出来
    surfaceDim = Color(0xFF0E0E14),
    surfaceBright = Color(0xFF2A2A3C),
    surfaceContainerLowest = Color(0xFF0F0F16),
    surfaceContainerLow = Color(0xFF191924),
    surfaceContainer = Color(0xFF1E1E2E),
    surfaceContainerHigh = Color(0xFF232334),
    surfaceContainerHighest = Color(0xFF2A2A3C),

    inverseSurface = Color(0xFF2E2E40),
    inverseOnSurface = Color(0xFFF2F2F7),
    inversePrimary = Color(0xFF00734C),

    outline = Color(0xFF4A4A5E),
    outlineVariant = Color(0xFF2F2F42),

    error = Color(0xFFFF5252),
    onError = Color.White,
    errorContainer = Color(0xFF4A1518),
    onErrorContainer = Color(0xFFFFB4AB),

    scrim = Color.Black
)

/**
 * 统一圆角比例（与现有卡片 / 弹层对齐）：
 * 4 = 小标签与进度条，8 = 输入框内部小块，12 = 常规卡片，
 * 16 = 大卡片，28 = 对话框（沿用 Material3 默认）。
 */
private val McilmShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun McilmTheme(content: @Composable () -> Unit) {
    val view = LocalView.current

    // 状态栏与导航栏统一成应用背景色，图标固定浅色，避免深色界面下出现浅色系统栏
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DarkBg.toArgb()
            window.navigationBarColor = DarkBg.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = DarkColorScheme,
        shapes = McilmShapes,
        content = content
    )
}
