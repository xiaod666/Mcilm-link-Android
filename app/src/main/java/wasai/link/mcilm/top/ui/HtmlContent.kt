package wasai.link.mcilm.top.ui

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun HtmlContent(html: String, modifier: Modifier = Modifier) {
    val styledHtml = remember(html) {
        """
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
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                settings.javaScriptEnabled = false
                settings.loadsImagesAutomatically = true
                settings.defaultTextEncodingName = "utf-8"
                settings.allowFileAccess = false
                settings.allowContentAccess = false
            }
        },
        update = { webView ->
            // 只有 HTML 真正改变时才重新 load，防止滚动导致闪烁
            if (webView.tag != styledHtml) {
                webView.tag = styledHtml
                webView.loadDataWithBaseURL(null, styledHtml, "text/html", "utf-8", null)
            }
        }
    )
}