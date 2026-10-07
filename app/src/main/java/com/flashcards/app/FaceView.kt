package com.flashcards.app

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private val latexCommand = Regex("\\\\[a-zA-Z]+")
private val longWord = Regex("[A-Za-z]{4,}")

/** True for pasted raw LaTeX such as  \frac{1}{2}mv^2  that has no $ or \( \) around it. */
private fun looksLikeRawLatex(t: String): Boolean {
    if (t.contains('$') || t.contains("\\(") || t.contains("\\[") || t.contains("\\begin")) return false
    if (!latexCommand.containsMatchIn(t)) return false
    val rest = latexCommand.replace(t, " ")
    return longWord.findAll(rest).count() <= 1
}

fun bodyHtml(items: List<Pair<String, String>>): String = items.joinToString("") { (type, data) ->
    val d = "\$\$"
    val t = data.trim()
    val shown = when {
        type == "LATEX" -> if (t.startsWith("$") || t.startsWith("\\(") || t.startsWith("\\[") || t.startsWith("\\begin")) t else d + t + d
        looksLikeRawLatex(t) -> d + t + d
        else -> data
    }
    "<div class='i'>${esc(shown)}</div>"
}

private const val SHELL =
    "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
        "<link rel='stylesheet' href='katex/katex.min.css'>" +
        "<script src='katex/katex.min.js'></script><script src='katex/auto-render.min.js'></script>" +
        "<script src='katex/shell.js'></script>" +
        "<style>html,body{margin:0;height:100%;background:transparent}" +
        "body{display:flex;font-family:sans-serif;font-size:21px;text-align:center;overflow-wrap:anywhere}" +
        "#c{margin:auto;padding:14px;max-width:100%}.i{margin:10px 0;white-space:pre-wrap}" +
        ".katex-display{overflow-x:auto;overflow-y:hidden;margin:.6em 0}</style></head>" +
        "<body><div id='c'></div></body></html>"

private class Holder {
    var ready = false
    var pending: String? = null
    var last: String? = null
}

/**
 * Renders text + LaTeX offline with bundled KaTeX. The page is loaded ONCE; later faces are pushed in with JavaScript,
 * which is much faster than reloading a page (this keeps card turns smooth). Content is centred.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun FaceView(items: List<Pair<String, String>>, dark: Boolean, modifier: Modifier = Modifier) {
    val payload = remember(items, dark) { "setContent(" + JSONObject.quote(bodyHtml(items)) + "," + (if (dark) "1" else "0") + ")" }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val h = Holder()
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                setBackgroundColor(0)
                tag = h
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        h.ready = true
                        val p = h.pending
                        if (p != null && view != null) {
                            h.last = p
                            view.evaluateJavascript(p, null)
                        }
                    }
                }
                loadDataWithBaseURL("file:///android_asset/", SHELL, "text/html", "utf-8", null)
            }
        },
        update = { wv ->
            val h = wv.tag as Holder
            h.pending = payload
            if (h.ready && h.last != payload) {
                h.last = payload
                wv.evaluateJavascript(payload, null)
            }
        }
    )
}
