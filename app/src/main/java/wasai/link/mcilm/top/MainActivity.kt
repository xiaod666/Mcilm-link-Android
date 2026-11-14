package wasai.link.mcilm.top

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import wasai.link.mcilm.top.link.MciLmNative
import wasai.link.mcilm.top.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 【P2-1 修复】提前初始化文件日志，防止启动阶段日志丢失
        MciLmNative.initFileLogging(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot()
                }
            }
        }
    }
}