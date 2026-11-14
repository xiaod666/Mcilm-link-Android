package wasai.link.mcilm.top.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView

@Composable
fun HtmlContent(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = false   // 公告不需要 JS
                settings.loadsImagesAutomatically = true
                settings.defaultTextEncodingName = "utf-8"
            }
        },
        update = { webView ->
            val styled = """
                <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>
                body{color:#E8E8E8;background:transparent;font-size:15px;line-height:1.7;padding:0 4px;word-break:break-word;}
                a{color:#00E676;}
                img{max-width:100%;height:auto;border-radius:6px;}
                blockquote{border-left:3px solid #666;margin:8px 0;padding-left:12px;color:#AAA;}
                h1,h2,h3{color:#FFF;line-height:1.3;}
                code,pre{background:#222;color:#0F0;padding:2px 4px;border-radius:4px;font-size:13px;}
                </style></head><body>$html</body></html>
            """.trimIndent()
            webView.loadDataWithBaseURL(null, styled, "text/html", "utf-8", null)
        },
        onRelease = { it.destroy() }
    )
}