package com.flashcards.app

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

fun buildHtml(items: List<Pair<String, String>>, dark: Boolean): String {
    val fg = if (dark) "#ECECEC" else "#111111"
    val body = items.joinToString("") { (type, data) ->
        when (type) {
            "LATEX" -> {
                val t = data.trim()
                val wrapped = if (t.startsWith("$") || t.startsWith("\\(") || t.startsWith("\\[")) t else "\$\$$t\$\$"
                "<div class='i'>${esc(wrapped)}</div>"
            }
            else -> "<div class='i'>${esc(data)}</div>"
        }
    }
    return "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<link rel='stylesheet' href='katex/katex.min.css'>" +
        "<script src='katex/katex.min.js'></script><script src='katex/auto-render.min.js'></script>" +
        "<script src='katex/render.js'></script>" +
        "<style>body{background:transparent;color:$fg;font-family:sans-serif;font-size:20px;margin:8px;text-align:center;overflow-wrap:anywhere}" +
        ".i{margin:10px 0}.katex-display{overflow-x:auto;overflow-y:hidden}</style></head><body>$body</body></html>"
}

/** Renders a face (text + LaTeX) offline using bundled KaTeX. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FaceView(items: List<Pair<String, String>>, dark: Boolean, modifier: Modifier = Modifier) {
    val html = remember(items, dark) { buildHtml(items, dark) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                setBackgroundColor(0)
            }
        },
        update = { wv ->
            if (wv.tag != html) {
                wv.tag = html
                wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
            }
        }
    )
}
