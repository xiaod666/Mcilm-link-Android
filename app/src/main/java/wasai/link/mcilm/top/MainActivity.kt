package wasai.link.mcilm.top

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import wasai.link.mcilm.top.link.MciLmNative
import wasai.link.mcilm.top.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 【P2-1 修复】提前初始化文件日志，防止启动阶段日志丢失
        MciLmNative.initFileLogging(this)

        // 主题与背景由 AppRoot 内的 McilmTheme + Scaffold 统一提供。
        // 这里不再包一层默认的 MaterialTheme（默认是浅色方案），
        // 否则首帧会先按浅色主题绘制一帧，表现为启动白闪。
        setContent {
            AppRoot()
        }
    }
}
