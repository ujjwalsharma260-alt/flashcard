@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.flashcards.app

import androidx.compose.foundation.Canvas
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------- model ----------

class InkPt(val x: Float, val y: Float, val p: Float)
class InkStroke(val color: Int, val width: Float, val pts: List<InkPt>)
class InkDoc(val w: Float, val h: Float, val strokes: List<InkStroke>)

private const val ERASER_R = 14f

object Ink {
    const val W = 1000f
    const val H = 750f

    fun serialize(doc: InkDoc): String {
        if (doc.strokes.isEmpty()) return ""
        val sb = StringBuilder("i1,${doc.w.roundToInt()},${doc.h.roundToInt()}")
        for (s in doc.strokes) {
            sb.append('|').append(s.color).append(',').append((s.width * 10).roundToInt())
            for (p in s.pts) {
                sb.append(',').append((p.x * 10).roundToInt())
                sb.append(',').append((p.y * 10).roundToInt())
                sb.append(',').append((p.p * 100).roundToInt())
            }
        }
        return sb.toString()
    }

    fun parse(data: String): InkDoc {
        try {
            if (data.isBlank()) return InkDoc(W, H, emptyList())
            val parts = data.split('|')
            val head = parts[0].split(',')
            val w = head[1].toFloat()
            val h = head[2].toFloat()
            val strokes = ArrayList<InkStroke>()
            for (k in 1 until parts.size) {
                val v = parts[k].split(',')
                if (v.size < 5) continue
                val pts = ArrayList<InkPt>()
                var i = 2
                while (i + 2 < v.size) {
                    pts.add(InkPt(v[i].toFloat() / 10f, v[i + 1].toFloat() / 10f, v[i + 2].toFloat() / 100f))
                    i += 3
                }
                strokes.add(InkStroke(v[0].toInt(), v[1].toFloat() / 10f, pts))
            }
            return InkDoc(w, h, strokes)
        } catch (e: Exception) {
            return InkDoc(W, H, emptyList())
        }
    }
}

fun colorOf(idx: Int, def: Color): Color = when (idx) {
    1 -> Color(0xFFE53935)
    2 -> Color(0xFF1E88E5)
    3 -> Color(0xFF43A047)
    else -> def
}

// ---------- smoothing + drawing ----------

private fun widthOf(s: InkStroke, p: Float): Float = s.width * (0.55f + 0.85f * p.coerceIn(0f, 1f))

private fun smooth(pts: List<InkPt>): List<InkPt> {
    val n = pts.size
    if (n <= 2) return pts
    val out = ArrayList<InkPt>(n * 3)
    out.add(pts[0])
    var ax = pts[0].x
    var ay = pts[0].y
    for (i in 1 until n - 1) {
        val c = pts[i]
        val nx = pts[i + 1]
        val mx = (c.x + nx.x) / 2f
        val my = (c.y + nx.y) / 2f
        for (s in 1..3) {
            val t = s / 3f
            val u = 1f - t
            out.add(InkPt(u * u * ax + 2f * u * t * c.x + t * t * mx, u * u * ay + 2f * u * t * c.y + t * t * my, c.p))
        }
        ax = mx
        ay = my
    }
    out.add(pts[n - 1])
    return out
}

private fun DrawScope.drawOne(s: InkStroke, scale: Float, ox: Float, oy: Float, color: Color) {
    if (s.pts.isEmpty()) return
    if (s.pts.size == 1) {
        val p = s.pts[0]
        drawCircle(color, widthOf(s, p.p) * scale / 2f, Offset(ox + p.x * scale, oy + p.y * scale))
        return
    }
    val pts = smooth(s.pts)
    var i = 0
    while (i < pts.size - 1) {
        val end = min(i + 6, pts.size - 1)
        val path = Path()
        path.moveTo(ox + pts[i].x * scale, oy + pts[i].y * scale)
        var wsum = widthOf(s, pts[i].p)
        for (j in i + 1..end) {
            path.lineTo(ox + pts[j].x * scale, oy + pts[j].y * scale)
            wsum += widthOf(s, pts[j].p)
        }
        val w = wsum / (end - i + 1) * scale
        drawPath(path, color, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        i = end
    }
}

private fun DrawScope.drawInk(strokes: List<InkStroke>, scale: Float, ox: Float, oy: Float, def: Color) {
    for (s in strokes) drawOne(s, scale, ox, oy, colorOf(s.color, def))
}

/** Renders all strokes into a static bitmap ONCE. This is what makes flips smooth. */
private fun renderInkBitmap(doc: InkDoc, wPx: Int, hPx: Int, fg: Color, density: Density): ImageBitmap {
    val bmp = ImageBitmap(wPx, hPx)
    val canvas = GraphicsCanvas(bmp)
    val sc = min(wPx / doc.w, hPx / doc.h)
    val ox = (wPx - doc.w * sc) / 2f
    val oy = (hPx - doc.h * sc) / 2f
    CanvasDrawScope().draw(
        density = density,
        layoutDirection = LayoutDirection.Ltr,
        canvas = canvas,
        size = Size(wPx.toFloat(), hPx.toFloat())
    ) {
        drawInk(doc.strokes, sc, ox, oy, fg)
    }
    return bmp
}

// ---------- eraser ----------

private fun distSeg(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val l2 = dx * dx + dy * dy
    if (l2 == 0f) return hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

private fun densify(pts: List<InkPt>, step: Float): List<InkPt> {
    val out = ArrayList<InkPt>(pts.size * 2)
    for (i in pts.indices) {
        out.add(pts[i])
        if (i + 1 < pts.size) {
            val a = pts[i]
            val b = pts[i + 1]
            val n = floor(hypot(b.x - a.x, b.y - a.y) / step).toInt()
            for (k in 1..n) {
                val t = k / (n + 1f)
                out.add(InkPt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.p + (b.p - a.p) * t))
            }
        }
    }
    return out
}

private fun eraseAt(strokes: List<InkStroke>, cx: Float, cy: Float, r: Float): List<InkStroke> {
    var changed = false
    val out = ArrayList<InkStroke>(strokes.size + 2)
    for (s in strokes) {
        val reach = r + s.width
        var near = false
        if (s.pts.size == 1) {
            near = hypot(cx - s.pts[0].x, cy - s.pts[0].y) <= reach
        } else {
            for (k in 0 until s.pts.size - 1) {
                if (distSeg(cx, cy, s.pts[k].x, s.pts[k].y, s.pts[k + 1].x, s.pts[k + 1].y) <= reach) { near = true; break }
            }
        }
        if (!near) { out.add(s); continue }
        changed = true
        var run = ArrayList<InkPt>()
        for (p in densify(s.pts, 3f)) {
            if (hypot(p.x - cx, p.y - cy) <= r) {
                if (run.size >= 2) out.add(InkStroke(s.color, s.width, run))
                run = ArrayList()
            } else run.add(p)
        }
        if (run.size >= 2) out.add(InkStroke(s.color, s.width, run))
    }
    return if (changed) out else strokes
}

// ---------- display ----------

/**
 * Read-only handwriting. The strokes are rendered ONCE into a bitmap and reused for every frame,
 * which is what makes the study screen card flip feel smooth (no per-frame path smoothing).
 */
@Composable
fun InkView(data: String, modifier: Modifier = Modifier, placeholder: Boolean = false, fit: Boolean = false, color: Color? = null) {
    val doc = remember(data) { Ink.parse(data) }
    val fg = color ?: MaterialTheme.colorScheme.onSurface
    if (doc.strokes.isEmpty()) {
        if (placeholder) {
            Box(
                modifier.height(120.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) { Text("✍ Tap to write") }
        }
    } else {
        val m = if (fit) modifier else modifier.aspectRatio(doc.w / doc.h)
        var sizeState by remember { mutableStateOf(IntSize.Zero) }
        val density = LocalDensity.current
        val bitmap = remember(doc, fg, sizeState, density) {
            if (sizeState.width > 0 && sizeState.height > 0) {
                renderInkBitmap(doc, sizeState.width, sizeState.height, fg, density)
            } else null
        }
        Canvas(m.clipToBounds().onSizeChanged { sizeState = it }) {
            bitmap?.let { drawImage(it) }
        }
    }
}

// ---------- editor ----------

@Composable
fun InkEditorDialog(initial: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
            InkEditorContent(initial, onDone, onCancel)
        }
    }
}

@Composable
private fun InkEditorContent(initial: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val doc0 = remember { Ink.parse(initial) }
    val isNew = initial.isBlank()
    var cvs by remember { mutableStateOf(IntSize.Zero) }
    var strokes by remember { mutableStateOf(doc0.strokes) }
    val undo = remember { mutableStateListOf<List<InkStroke>>() }
    val redo = remember { mutableStateListOf<List<InkStroke>>() }
    var tool by remember { mutableStateOf(0) }
    var colorIdx by remember { mutableStateOf(0) }
    var widthIdx by remember { mutableStateOf(1) }
    var penOnly by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var eraserPos by remember { mutableStateOf<Offset?>(null) }
    var lastStylus by remember { mutableStateOf(0L) }
    val livePts = remember { ArrayList<InkPt>() }
    val widths = remember { floatArrayOf(2.2f, 3.6f, 6f) }
    val fg = MaterialTheme.colorScheme.onSurface
    val pageBg = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outline

    fun pageH(w: Int, h: Int): Float =
        if (isNew && w > 0 && h > 0) (doc0.w * h / w).coerceIn(500f, 2600f) else doc0.h

    fun commit(before: List<InkStroke>) {
        if (strokes !== before) {
            undo.add(before)
            if (undo.size > 100) undo.removeAt(0)
            redo.clear()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(onClick = { if (undo.isNotEmpty() || redo.isNotEmpty()) confirmClose = true else onCancel() }) {
                Icon(Icons.Default.Close, contentDescription = "Discard")
            }
            IconButton(onClick = { onDone(Ink.serialize(InkDoc(doc0.w, pageH(cvs.width, cvs.height), strokes))) }) {
                Icon(Icons.Default.Check, contentDescription = "Done")
            }
            TextButton(enabled = undo.isNotEmpty(), onClick = {
                val prev = undo.removeAt(undo.lastIndex)
                redo.add(strokes)
                strokes = prev
            }) { Text("↶ Undo") }
            TextButton(enabled = redo.isNotEmpty(), onClick = {
                val nxt = redo.removeAt(redo.lastIndex)
                undo.add(strokes)
                strokes = nxt
            }) { Text("Redo ↷") }
            FilterChip(selected = tool == 0, onClick = { tool = 0 }, label = { Text("✏ Pen") })
            FilterChip(selected = tool == 1, onClick = { tool = 1 }, label = { Text("⌫ Eraser") })
            for (ci in 0..3) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(colorOf(ci, fg))
                        .border(if (colorIdx == ci && tool == 0) 3.dp else 1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable { colorIdx = ci; tool = 0 }
                )
            }
            listOf("S", "M", "L").forEachIndexed { i, l ->
                FilterChip(selected = widthIdx == i, onClick = { widthIdx = i; tool = 0 }, label = { Text(l) })
            }
            TextButton(enabled = strokes.isNotEmpty(), onClick = {
                val before = strokes
                strokes = emptyList()
                commit(before)
            }) { Text("Clear") }
            Switch(checked = penOnly, onCheckedChange = { penOnly = it })
            Text("Pen only")
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { cvs = it }) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { }) {
                val ph = pageH(size.width.toInt(), size.height.toInt())
                val sc = min(size.width / doc0.w, size.height / ph)
                val ox = (size.width - doc0.w * sc) / 2f
                val oy = (size.height - ph * sc) / 2f
                drawRect(pageBg, Offset(ox, oy), Size(doc0.w * sc, ph * sc))
                drawRect(outline, Offset(ox, oy), Size(doc0.w * sc, ph * sc), style = Stroke(2f))
                drawInk(strokes, sc, ox, oy, fg)
            }
            Canvas(
                Modifier.fillMaxSize().pointerInput(tool, colorIdx, widthIdx, penOnly) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val stylus = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                        val nowMs = System.currentTimeMillis()
                        if (!stylus && (penOnly || nowMs - lastStylus < 900L)) return@awaitEachGesture
                        if (stylus) lastStylus = nowMs

                        val ph = pageH(size.width, size.height)
                        val sc = min(size.width / doc0.w, size.height / ph)
                        val ox = (size.width - doc0.w * sc) / 2f
                        val oy = (size.height - ph * sc) / 2f
                        fun toPage(o: Offset) = Offset(((o.x - ox) / sc).coerceIn(0f, doc0.w), ((o.y - oy) / sc).coerceIn(0f, ph))

                        val erasing = tool == 1 || down.type == PointerType.Eraser
                        val before = strokes

                        if (erasing) {
                            var last = toPage(down.position)
                            eraserPos = last
                            strokes = eraseAt(strokes, last.x, last.y, ERASER_R)
                            do {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                val positions = ch.historical.map { it.position } + ch.position
                                for (po in positions) {
                                    val cur = toPage(po)
                                    val d = hypot(cur.x - last.x, cur.y - last.y)
                                    val steps = max(1, ceil(d / (ERASER_R / 2f)).toInt())
                                    for (k in 1..steps) {
                                        val t = k / steps.toFloat()
                                        strokes = eraseAt(strokes, last.x + (cur.x - last.x) * t, last.y + (cur.y - last.y) * t, ERASER_R)
                                    }
                                    last = cur
                                }
                                eraserPos = last
                                ch.consume()
                            } while (ch.pressed)
                            eraserPos = null
                            commit(before)
                        } else {
                            livePts.clear()
                            val base = widths[widthIdx]
                            fun addRaw(po: Offset, pr: Float) {
                                val pg = toPage(po)
                                val p = if (stylus) pr.coerceIn(0.12f, 1f) else 0.5f
                                if (livePts.isEmpty()) {
                                    livePts.add(InkPt(pg.x, pg.y, p))
                                } else {
                                    val prev = livePts[livePts.size - 1]
                                    val dist = hypot(pg.x - prev.x, pg.y - prev.y)
                                    val a = 0.35f + min(dist / 12f, 0.55f)
                                    val nx = prev.x + (pg.x - prev.x) * a
                                    val ny = prev.y + (pg.y - prev.y) * a
                                    val np = prev.p * 0.7f + p * 0.3f
                                    if (hypot(nx - prev.x, ny - prev.y) >= 0.4f) livePts.add(InkPt(nx, ny, np))
                                }
                            }
                            var lastRaw = down.position
                            addRaw(down.position, down.pressure)
                            tick++
                            do {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                for (h in ch.historical) addRaw(h.position, ch.pressure)
                                addRaw(ch.position, ch.pressure)
                                lastRaw = ch.position
                                tick++
                                ch.consume()
                            } while (ch.pressed)
                            if (livePts.isNotEmpty()) {
                                val e = toPage(lastRaw)
                                livePts.add(InkPt(e.x, e.y, livePts[livePts.size - 1].p))
                                strokes = strokes + InkStroke(colorIdx, base, ArrayList(livePts))
                                commit(before)
                            }
                            livePts.clear()
                            tick++
                        }
                    }
                }
            ) {
                @Suppress("UNUSED_VARIABLE") val subscribe = tick
                val ph = pageH(size.width.toInt(), size.height.toInt())
                val sc = min(size.width / doc0.w, size.height / ph)
                val ox = (size.width - doc0.w * sc) / 2f
                val oy = (size.height - ph * sc) / 2f
                if (livePts.isNotEmpty()) {
                    drawOne(InkStroke(colorIdx, widths[widthIdx], livePts.toList()), sc, ox, oy, colorOf(colorIdx, fg))
                }
                eraserPos?.let { drawCircle(fg.copy(alpha = 0.6f), ERASER_R * sc, Offset(ox + it.x * sc, oy + it.y * sc), style = Stroke(2f)) }
            }
        }
    }
    if (confirmClose) AlertDialog(
        onDismissRequest = { confirmClose = false },
        title = { Text("Discard your handwriting changes?") },
        confirmButton = { TextButton(onClick = { confirmClose = false; onCancel() }) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep writing") } }
    )
}
