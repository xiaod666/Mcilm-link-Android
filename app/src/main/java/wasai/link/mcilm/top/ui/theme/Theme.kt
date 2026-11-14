package wasai.link.mcilm.top.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val NeonGreen = Color(0xFF00FF88)
val DarkBg = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E2E)

private val DarkColorScheme = darkColorScheme(
    primary = NeonGreen,
    onPrimary = Color.Black,
    secondary = Color(0xFF00E5FF),
    onSecondary = Color.Black,
    background = DarkBg,
    surface = DarkSurface,
    onBackground = Color.White,
    onSurface = Color.White,
    error = Color(0xFFFF5252)
)

@Composable
fun McilmTheme(content: @Composable () -> Unit) {
    // 适配状态栏透明和深色图标
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DarkBg.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(colorScheme = DarkColorScheme, content = content)
}