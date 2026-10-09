@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.flashcards.app

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SavedSession(
    val order: List<Long>, val answered: Set<Int>, val pos: Int, val total: Int,
    val missed: Set<Long>, val correct: Int, val wrong: Int, val streak: Int
)

object Resume {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("resume", 0)
    private fun key(deck: Long, mode: Int) = "s_${deck}_$mode"

    fun save(ctx: Context, deck: Long, mode: Int, s: SavedSession) {
        try {
            val o = JSONObject()
            o.put("order", JSONArray(s.order))
            o.put("answered", JSONArray(s.answered.toList()))
            o.put("pos", s.pos)
            o.put("total", s.total)
            o.put("missed", JSONArray(s.missed.toList()))
            o.put("correct", s.correct)
            o.put("wrong", s.wrong)
            o.put("streak", s.streak)
            prefs(ctx).edit().putString(key(deck, mode), o.toString()).apply()
        } catch (e: Exception) { }
    }

    fun load(ctx: Context, deck: Long, mode: Int): SavedSession? {
        val str = prefs(ctx).getString(key(deck, mode), null) ?: return null
        return try {
            val o = JSONObject(str)
            fun longs(a: JSONArray): List<Long> = (0 until a.length()).map { a.getLong(it) }
            SavedSession(
                longs(o.getJSONArray("order")),
                (0 until o.getJSONArray("answered").length()).map { o.getJSONArray("answered").getInt(it) }.toSet(),
                o.getInt("pos"), o.getInt("total"),
                longs(o.getJSONArray("missed")).toSet(),
                o.getInt("correct"), o.getInt("wrong"), o.getInt("streak")
            )
        } catch (e: Exception) { null }
    }

    fun clear(ctx: Context, deck: Long, mode: Int) { prefs(ctx).edit().remove(key(deck, mode)).apply() }

    fun choice(ctx: Context, deck: Long): Int = prefs(ctx).getInt("c_$deck", 0)
    fun setChoice(ctx: Context, deck: Long, v: Int) { prefs(ctx).edit().putInt("c_$deck", v).apply() }
    fun forgetChoices(ctx: Context) {
        val e = prefs(ctx).edit()
        for (k in prefs(ctx).all.keys) if (k.startsWith("c_")) e.remove(k)
        e.apply()
    }
}

private class Undo(
    val kind: Int,
    val pos: Int,
    val before: Flashcard?,
    val logId: Deferred<Long>?,
    val appended: Boolean,
    val missedWas: Set<Long>,
    val c: Int, val w: Int, val s: Int
)

private fun nextUnanswered(o: List<Long>, ans: Set<Int>, from: Int): Int {
    if (o.isEmpty()) return -1
    for (k in 1..o.size) {
        val i = (from + k) % o.size
        if (i !in ans) return i
    }
    return -1
}

private fun animationsOn(ctx: Context): Boolean =
    try { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f } catch (e: Exception) { true }

private val swipeEase = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
private val flipInEase = CubicBezierEasing(0.34f, 1.2f, 0.64f, 1f)
private val grayText = Color(0xFF8A8A92)
private val tickGreen = Color(0xFF4CAF50)
private val crossRed = Color(0xFFEF5350)

// Card theme helpers — 0 Black, 1 Green board, 2 Whiteboard, 3 Paper, 4 Lined
private fun cardBgOf(t: Int): Color = when (t) {
    1 -> Color(0xFF1E3D2A)
    2 -> Color(0xFFF4F4F4)
    3 -> Color(0xFFF5EBD8)
    4 -> Color(0xFFFCF6E3)
    else -> Color.Black
}
private fun cardFgOf(t: Int): Color = if (t <= 1) Color.White else Color(0xFF1A1A1A)

@Composable
fun ReviewScreen(db: Db, deckId: Long, mode: Int, openDeck: () -> Unit, back: () -> Unit) {
    MaterialTheme(colorScheme = darkPalette(0)) {
        ReviewContent(db, deckId, mode, openDeck, back)
    }
}

@Composable
private fun ReviewContent(db: Db, deckId: Long, mode: Int, openDeck: () -> Unit, back: () -> Unit) {
    val dao = db.dao()
    val ctx = LocalContext.current
    val dens = LocalDensity.current
    val config = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val screenW = with(dens) { config.screenWidthDp.dp.toPx() }
    val screenH = with(dens) { config.screenHeightDp.dp.toPx() }
    val thr = with(dens) { 56.dp.toPx() }
    val anim = remember { animationsOn(ctx) }

    var order by remember { mutableStateOf<List<Long>?>(null) }
    var answered by remember { mutableStateOf(setOf<Int>()) }
    var pos by remember { mutableStateOf(0) }
    var face by remember { mutableStateOf(0) }
    var setNo by remember { mutableStateOf(1) }
    var setTotal by remember { mutableStateOf(0) }
    var missed by remember { mutableStateOf(setOf<Long>()) }
    var correctCnt by remember { mutableStateOf(0) }
    var wrongCnt by remember { mutableStateOf(0) }
    var streak by remember { mutableStateOf(0) }
    var history by remember { mutableStateOf(listOf<Undo>()) }
    var busy by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf<String?>(null) }
    var resumeAsk by remember { mutableStateOf<SavedSession?>(null) }
    var rememberChoice by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showCardMenu by remember { mutableStateOf(false) }
    val cache = remember { mutableStateMapOf<Long, Pair<Flashcard, List<List<Item>>>>() }
    val gone = remember { mutableStateListOf<Long>() }
    val rot = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val offX = remember { Animatable(0f) }
    val offY = remember { Animatable(0f) }

    var iconType by remember { mutableStateOf(0) }
    var iconTrigger by remember { mutableStateOf(0) }
    val iconAlpha = remember { Animatable(0f) }
    val iconScale = remember { Animatable(0.5f) }

    var showSpeedDialog by remember { mutableStateOf(false) }
    var showSkipDialog by remember { mutableStateOf(false) }
    var showBrowseDialog by remember { mutableStateOf(false) }
    var skipText by remember { mutableStateOf("") }

    var speedActive by remember { mutableStateOf(false) }
    var speedMode by remember { mutableStateOf(0) }
    var speedSeconds by remember { mutableStateOf(10) }
    var speedRemaining by remember { mutableStateOf(0) }
    var speedCorrect by remember { mutableStateOf(0) }
    var speedWrong by remember { mutableStateOf(0) }

    var browseActive by remember { mutableStateOf(false) }
    var browseMode by remember { mutableStateOf(0) }
    var browseDelay by remember { mutableStateOf(6) }
    var browseRemaining by remember { mutableStateOf(0) }

    LaunchedEffect(iconTrigger) {
        if (iconTrigger == 0) return@LaunchedEffect
        iconScale.snapTo(0.5f)
        iconAlpha.snapTo(0f)
        coroutineScope {
            launch { iconScale.animateTo(1f, tween(if (anim) 220 else 0, easing = flipInEase)) }
            launch { iconAlpha.animateTo(1f, tween(if (anim) 150 else 0)) }
        }
        delay(380L)
        coroutineScope {
            launch { iconAlpha.animateTo(0f, tween(if (anim) 180 else 0)) }
            launch { iconScale.animateTo(1.15f, tween(if (anim) 180 else 0)) }
        }
        iconType = 0
    }

    fun curCard(): Pair<Flashcard, List<List<Item>>>? {
        val o = order ?: return null
        val id = o.getOrNull(pos) ?: return null
        return cache[id]
    }

    fun persist() {
        val o = order ?: return
        Resume.save(ctx, deckId, mode, SavedSession(o, answered, pos, setTotal, missed, correctCnt, wrongCnt, streak))
    }

    suspend fun startFresh() {
        val q0 = Session.build(dao, deckId, mode, System.currentTimeMillis())
        order = q0
        answered = emptySet(); pos = 0; face = 0; setTotal = q0.size
        missed = emptySet(); correctCnt = 0; wrongCnt = 0; streak = 0; history = emptyList()
        Resume.clear(ctx, deckId, mode)
    }

    fun applySaved(sv: SavedSession) {
        order = sv.order
        answered = sv.answered; pos = sv.pos.coerceIn(0, max(0, sv.order.lastIndex)); face = 0; setTotal = sv.total
        missed = sv.missed; correctCnt = sv.correct; wrongCnt = sv.wrong; streak = sv.streak; history = emptyList()
    }

    LaunchedEffect(setNo) {
        order = null
        val saved = if (setNo == 1) Resume.load(ctx, deckId, mode) else null
        if (saved != null && saved.order.isNotEmpty() && saved.answered.size < saved.order.size) {
            when (Resume.choice(ctx, deckId)) {
                1 -> applySaved(saved)
                2 -> startFresh()
                else -> resumeAsk = saved
            }
        } else {
            startFresh()
        }
    }

    LaunchedEffect(order, pos) {
        val o = order ?: return@LaunchedEffect
        val want = ArrayList<Long>()
        if (pos in o.indices) want.add(o[pos])
        var p = pos
        for (k in 0 until 3) {
            p = nextUnanswered(o, answered, p)
            if (p < 0 || p == pos) break
            want.add(o[p])
        }
        for (id in want) {
            if (id in cache || id in gone) continue
            val c = dao.card(id)
            if (c == null) gone.add(id)
            else cache[id] = Pair(c, dao.items(id).groupBy { it.face }.toSortedMap().values.toList())
        }
    }

    val curIdNow = order?.getOrNull(pos)
    LaunchedEffect(curIdNow, gone.size) {
        val o = order
        if (o != null && curIdNow != null && curIdNow in gone) {
            answered = answered + pos
            val np = nextUnanswered(o, answered, pos)
            if (np >= 0) { pos = np; face = 0 }
            persist()
        }
    }

    val finishedNow = order.let { it != null && it.isNotEmpty() && answered.size >= it.size }
    LaunchedEffect(finishedNow) { if (finishedNow) Resume.clear(ctx, deckId, mode) }

    LaunchedEffect(pos, speedActive) {
        if (!speedActive) return@LaunchedEffect
        speedRemaining = speedSeconds
        var remaining = speedSeconds
        while (remaining > 0) {
            delay(1000L)
            remaining -= 1
            speedRemaining = remaining
        }
        val cd = curCard() ?: return@LaunchedEffect
        if (face < cd.second.lastIndex) {
            face = cd.second.lastIndex
        }
    }

    fun advance() {
        if (busy) return
        val cd = curCard() ?: return
        if (face >= cd.second.lastIndex) return
        busy = true
        scope.launch {
            val style = if (anim) AppSettings.flipStyle else 2
            when (style) {
                2 -> { face += 1 }
                1 -> {
                    rot.animateTo(90f, tween(220, easing = FastOutSlowInEasing))
                    face += 1
                    rot.snapTo(-90f)
                    delay(45L)
                    rot.animateTo(0f, tween(280, easing = flipInEase))
                }
                else -> {
                    fade.animateTo(0f, tween(110))
                    face += 1
                    delay(50L)
                    fade.animateTo(1f, tween(150))
                }
            }
            busy = false
        }
    }

    fun answer(rating: Int) {
        val o = order ?: return
        val cd = curCard() ?: return
        val c = cd.first
        val now = System.currentTimeMillis()
        val onSchedule = c.state == 0 || c.due <= now
        val n = if (onSchedule) {
            Scheduler.next(c, rating, now, if (AppSettings.streakBonus) AppSettings.streakN else 0, AppSettings.streakMult.toDouble())
        } else {
            Scheduler.statsOnly(c, rating, now)
        }
        val job = scope.async {
            dao.updateCard(n)
            dao.log(ReviewLog(cardId = c.id, time = now, rating = rating, interval = n.interval))
        }
        val wrong = rating == 0
        val again = wrong && AppSettings.retryMissed
        history = (history + Undo(1, pos, c, job, again, missed, correctCnt, wrongCnt, streak)).takeLast(200)
        cache[c.id] = Pair(n, cd.second)
        if (wrong) { missed = missed + c.id; wrongCnt += 1; streak = 0 } else { correctCnt += 1; streak += 1 }
        if (speedActive) {
            if (wrong) speedWrong += 1 else speedCorrect += 1
        }
        val newOrder = if (again) o + c.id else o
        val newAns = answered + pos
        order = newOrder
        answered = newAns
        val np = nextUnanswered(newOrder, newAns, pos)
        if (np >= 0) { pos = np; face = 0 }
        persist()
    }

    fun navNext() {
        val o = order ?: return
        val np = nextUnanswered(o, answered, pos)
        if (np < 0 || np == pos) return
        history = (history + Undo(0, pos, null, null, false, emptySet(), 0, 0, 0)).takeLast(200)
        pos = np; face = 0
        persist()
    }

    fun navPrevKeepRating() {
        if (pos > 0) {
            pos = pos - 1
            face = 0
            persist()
        }
    }

    fun goBack() {
        val h = history.lastOrNull() ?: return
        history = history.dropLast(1)
        if (h.kind == 0) {
            pos = h.pos; face = 0
        } else {
            val o = order ?: return
            order = if (h.appended) o.dropLast(1) else o
            answered = answered - h.pos
            pos = h.pos; face = 0
            missed = h.missedWas; correctCnt = h.c; wrongCnt = h.w; streak = h.s
            val before = h.before
            if (before != null) {
                val items = cache[before.id]?.second
                if (items != null) cache[before.id] = Pair(before, items)
                scope.launch {
                    dao.updateCard(before)
                    val lid = h.logId
                    if (lid != null) dao.deleteLog(lid.await())
                }
            }
        }
        persist()
    }

    LaunchedEffect(pos, face, browseActive) {
        if (!browseActive) return@LaunchedEffect
        val cd = curCard() ?: return@LaunchedEffect
        val currentFace = cd.second.getOrNull(face.coerceAtMost(cd.second.lastIndex))
        val audio = currentFace?.firstOrNull { it.type == "AUDIO" }
        if (audio != null) {
            Player.stop()
            Player.play(ctx, audio.data) { }
        }
        browseRemaining = browseDelay
        var remaining = browseDelay
        while (remaining > 0) {
            delay(1000L)
            remaining -= 1
            browseRemaining = remaining
        }
        if (face < cd.second.lastIndex) {
            face = cd.second.lastIndex
        } else {
            navNext()
        }
    }

    LaunchedEffect(browseActive) {
        if (!browseActive) Player.stop()
    }

    fun springBack() {
        scope.launch {
            coroutineScope {
                launch { offX.animateTo(0f, spring()) }
                launch { offY.animateTo(0f, spring()) }
            }
        }
    }

    fun rateAndFly(dir: Int, rating: Int, icon: Int) {
        if (busy) return
        busy = true
        iconType = icon
        iconTrigger += 1
        scope.launch {
            val dur = if (anim) 300 else 0
            val tx = if (dir == 2) -screenW else 0f
            val ty = when (dir) {
                0 -> -screenH
                1 -> screenH
                else -> 0f
            }
            coroutineScope {
                launch { offX.animateTo(tx, tween(dur, easing = swipeEase)) }
                launch { offY.animateTo(ty, tween(dur, easing = swipeEase)) }
                launch { fade.animateTo(0f, tween(dur)) }
            }
            answer(rating)
            offX.snapTo(0f)
            offY.snapTo(0f)
            delay(40L)
            fade.animateTo(1f, tween(if (anim) 170 else 0))
            busy = false
        }
    }

    fun navAnim(forward: Boolean) {
        if (busy) return
        val o = order ?: return
        if (forward) {
            val np = nextUnanswered(o, answered, pos)
            if (np < 0 || np == pos) { springBack(); return }
        } else {
            if (pos <= 0) { springBack(); return }
        }
        busy = true
        scope.launch {
            val dur = if (anim) 280 else 0
            val tx = (if (forward) 1f else -1f) * screenW
            coroutineScope {
                launch { offX.animateTo(tx, tween(dur, easing = swipeEase)) }
                launch { fade.animateTo(0f, tween(dur)) }
            }
            if (forward) navNext() else navPrevKeepRating()
            offY.snapTo(0f)
            offX.snapTo(-tx * 0.5f)
            delay(40L)
            coroutineScope {
                launch { offX.animateTo(0f, tween(if (anim) 260 else 0, easing = swipeEase)) }
                launch { fade.animateTo(1f, tween(if (anim) 200 else 0)) }
            }
            busy = false
        }
    }

    fun release() {
        if (busy) return
        if (speedActive || browseActive) {
            val dx = offX.value
            val dy = offY.value
            if (abs(dy) > abs(dx) && abs(dy) > thr) {
                val cd = curCard()
                val isLast = cd == null || face >= cd.second.lastIndex
                if (!isLast) { springBack(); advance() }
                else if (dy < 0) rateAndFly(0, 3, 2) else rateAndFly(1, 0, 3)
            } else {
                springBack()
            }
            return
        }
        val dx = offX.value
        val dy = offY.value
        val cd = curCard()
        val isLast = cd == null || face >= cd.second.lastIndex
        val vertical = abs(dy) > abs(dx)

        if (AppSettings.ratingStyle == 0 && vertical && abs(dy) > thr) {
            if (!isLast) { springBack(); advance() }
            else if (dy < 0) rateAndFly(0, 3, 2) else rateAndFly(1, 0, 3)
        } else if (!vertical && abs(dx) > thr) {
            if (dx > 0) {
                navAnim(false)
            } else {
                if (AppSettings.ratingStyle == 0) {
                    if (!isLast) { springBack(); advance() } else rateAndFly(2, 2, 1)
                } else {
                    navAnim(true)
                }
            }
        } else {
            springBack()
        }
    }

    fun shuffleSet() {
        val o = order ?: return
        order = o.shuffled()
        answered = emptySet()
        pos = 0
        face = 0
        persist()
        toast(ctx, "Cards shuffled")
    }

    fun startSpeedGame() {
        val o = order ?: return
        val newOrder = when (speedMode) {
            0 -> o.shuffled()
            1 -> o
            else -> o
        }
        order = newOrder
        if (speedMode != 2) { pos = 0 } else { pos = pos.coerceIn(0, max(0, newOrder.lastIndex)) }
        answered = emptySet()
        face = 0
        speedCorrect = 0
        speedWrong = 0
        speedRemaining = speedSeconds
        speedActive = true
        browseActive = false
        persist()
    }

    fun startBrowseMode() {
        val o = order ?: return
        val newOrder = when (browseMode) {
            0 -> o.shuffled()
            1 -> o
            else -> o
        }
        order = newOrder
        if (browseMode != 2) { pos = 0 } else { pos = pos.coerceIn(0, max(0, newOrder.lastIndex)) }
        answered = emptySet()
        face = 0
        browseRemaining = browseDelay
        browseActive = true
        speedActive = false
        persist()
    }

    fun stopSpeedAndBrowse() {
        speedActive = false
        browseActive = false
        Player.stop()
    }

    fun toggleFlag(bookmark: Boolean) {
        val cd = curCard() ?: return
        val c = cd.first
        val n = if (bookmark) c.copy(bookmark = if (c.bookmark == 1) 0 else 1) else c.copy(fav = if (c.fav == 1) 0 else 1)
        cache[c.id] = Pair(n, cd.second)
        scope.launch { dao.updateCard(n) }
    }

    val o = order
    val cardShape = RoundedCornerShape(28.dp)
    val titleText = if (mode == 0) "" else listOf("", "Bookmarked", "Favorites", "Weak cards", "Missed today")[mode.coerceIn(0, 4)]
    val cardTheme = AppSettings.cardTheme
    val cardBg = cardBgOf(cardTheme)
    val cardFg = cardFgOf(cardTheme)
    val faceDark = cardTheme <= 1

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    stopSpeedAndBrowse()
                    back()
                }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = grayText) }
                Text(titleText, color = grayText, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = { showSettings = true }) { Text("⚙", color = grayText, fontSize = 24.sp) }
            }
            val total = max(setTotal, 1)
            LinearProgressIndicator(
                progress = { (answered.count { it < setTotal }.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Color(0xFF6E6E78), trackColor = Color.Transparent
            )
            when {
                o == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("Loading...", color = grayText) }
                o.isEmpty() -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nothing to study right now 🎉", color = Color.White, fontSize = 22.sp)
                    Spacer(Modifier.height(18.dp))
                    Button(onClick = openDeck) { Text("Browse cards") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = back) { Text("Back") }
                }
                finishedNow -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (speedActive) {
                        Text("Speed game complete! 🎉", color = Color.White, fontSize = 24.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("Correct: $speedCorrect", color = tickGreen, fontSize = 22.sp)
                        Text("Wrong: $speedWrong", color = crossRed, fontSize = 22.sp)
                        Spacer(Modifier.height(20.dp))
                        if (missed.isNotEmpty()) {
                            Button(onClick = {
                                val missedIds = missed.toList()
                                order = missedIds
                                answered = emptySet()
                                pos = 0
                                face = 0
                                setTotal = missedIds.size
                                missed = emptySet()
                                speedCorrect = 0
                                speedWrong = 0
                                speedRemaining = speedSeconds
                                persist()
                            }) { Text("Redo wrong ones (${missed.size})") }
                            Spacer(Modifier.height(8.dp))
                        }
                        Button(onClick = { stopSpeedAndBrowse() }) { Text("Finish") }
                    } else {
                        Text("Set $setNo complete! 🎉", color = Color.White, fontSize = 24.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("You studied $setTotal cards.  Missed at first: ${missed.size}", color = grayText)
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { setNo += 1 }) { Text("Next set") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = back) { Text("Finish") }
                    }
                }
                else -> {
                    val cd = curCard()
                    val faceList = cd?.second ?: emptyList()
                    val fi = if (faceList.isEmpty()) 0 else face.coerceIn(0, faceList.lastIndex)
                    val isLast = faceList.isEmpty() || fi >= faceList.lastIndex
                    val faceItems = faceList.getOrElse(fi) { emptyList() }
                    val textItems = faceItems.filter { it.type == "TEXT" || it.type == "LATEX" }
                    val imgs = faceItems.filter { it.type == "IMAGE" }
                    val inks = faceItems.filter { it.type == "INK" }
                    val auds = faceItems.filter { it.type == "AUDIO" }
                    val hasText = textItems.isNotEmpty()
                    val visuals = imgs.isNotEmpty() || inks.isNotEmpty()
                    val showBorder = AppSettings.showBorder
                    val borderCol = if (cardTheme <= 1) Color(0xFF2E2E34) else Color(0x33000000)

                    Box(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
                            .graphicsLayer {
                                translationX = offX.value
                                translationY = offY.value
                                rotationZ = offX.value / 60f
                                rotationY = rot.value
                                alpha = fade.value
                                cameraDistance = 12f * density
                                scaleX = 0.97f + 0.03f * fade.value
                                scaleY = 0.97f + 0.03f * fade.value
                            }
                            .clip(cardShape)
                            .background(cardBg)
                            .then(if (showBorder) Modifier.border(1.5.dp, borderCol, cardShape) else Modifier)
                    ) {
                        // Lined paper background (faint horizontal rules, drawn behind content)
                        if (cardTheme == 4) {
                            Canvas(Modifier.matchParentSize()) {
                                val gap = 42.dp.toPx()
                                var y = gap
                                val lc = Color(0x222A2A2A)
                                while (y < size.height) {
                                    drawLine(lc, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.4f)
                                    y += gap
                                }
                            }
                        }
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Box(Modifier.then(if (hasText || !visuals) Modifier.weight(1f) else Modifier.height(1.dp)).fillMaxWidth()) {
                                FaceView(textItems.map { it.type to it.data }, faceDark, Modifier.fillMaxSize())
                            }
                            if (visuals) {
                                if (!hasText && imgs.isEmpty() && inks.size == 1) {
                                    InkView(inks[0].data, Modifier.weight(1f).fillMaxWidth(), fit = true, color = cardFg)
                                } else {
                                    Box(
                                        Modifier.then(if (hasText) Modifier.heightIn(max = 240.dp) else Modifier.weight(1f)).fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(Modifier.verticalScroll(rememberScrollState())) {
                                            imgs.forEach { im -> ImageThumb(im.data, Modifier.fillMaxWidth().padding(4.dp)) }
                                            inks.forEach { ik -> InkView(ik.data, Modifier.fillMaxWidth().padding(4.dp), color = cardFg) }
                                        }
                                    }
                                }
                            }
                        }
                        // Swipe tint: green when dragging up/left, red when dragging down. Full card area, up to 25% opacity.
                        Box(Modifier.matchParentSize().drawBehind {
                            val up = -offY.value
                            val left = -offX.value
                            val down = offY.value
                            val gAmt = (maxOf(up, left) / thr).coerceIn(0f, 1f) * 0.25f
                            val rAmt = (maxOf(down, 0f) / thr).coerceIn(0f, 1f) * 0.25f
                            if (gAmt > 0f) drawRect(Color(0xFF4CAF50).copy(alpha = gAmt), size = size)
                            if (rAmt > 0f) drawRect(Color(0xFFEF5350).copy(alpha = rAmt), size = size)
                        })
                        // Drag gesture layer (on top so it receives all input)
                        Box(
                            Modifier.matchParentSize()
                                .pointerInput(pos, face) {
                                    detectTapGestures(
                                        onTap = { advance() },
                                        onLongPress = { if (imgs.isNotEmpty()) zoom = imgs[0].data }
                                    )
                                }
                                .pointerInput(pos, face) {
                                    var dx = 0f
                                    var dy = 0f
                                    var locked = 0
                                    detectDragGestures(
                                        onDragStart = { dx = offX.value; dy = offY.value; locked = 0 },
                                        onDrag = { change, amt ->
                                            if (!busy) {
                                                change.consume()
                                                if (locked == 0) {
                                                    if (abs(amt.x) > 2f || abs(amt.y) > 2f) {
                                                        locked = if (abs(amt.x) > abs(amt.y)) 1 else 2
                                                    }
                                                }
                                                if (locked == 1) {
                                                    dx += amt.x
                                                    scope.launch { offX.snapTo(dx) }
                                                } else if (locked == 2) {
                                                    dy += amt.y
                                                    scope.launch { offY.snapTo(dy) }
                                                }
                                            }
                                        },
                                        onDragEnd = { release() },
                                        onDragCancel = { springBack() }
                                    )
                                }
                        )
                    }
                    auds.forEach { AudioPlayButton(it.data) }
                    if (AppSettings.ratingStyle == 1 && isLast) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Again", "Hard", "Good", "Easy").forEachIndexed { i, l ->
                                Button(onClick = {
                                    when (i) {
                                        0 -> rateAndFly(1, 0, 3)
                                        1 -> rateAndFly(1, 1, 3)
                                        2 -> rateAndFly(2, 2, 1)
                                        else -> rateAndFly(0, 3, 2)
                                    }
                                }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text(l) }
                            }
                        }
                    }

                    if (speedActive) {
                        LinearProgressIndicator(
                            progress = { speedRemaining.toFloat() / max(speedSeconds, 1).toFloat() },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = tickGreen, trackColor = Color(0xFF333333)
                        )
                        Text("${speedRemaining}s", color = grayText, fontSize = 12.sp)
                    }
                    if (browseActive) {
                        LinearProgressIndicator(
                            progress = { browseRemaining.toFloat() / max(browseDelay, 1).toFloat() },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = Color(0xFF64B5F6), trackColor = Color(0xFF333333)
                        )
                        Text("${browseRemaining}s", color = grayText, fontSize = 12.sp)
                    }

                    val shown = min(answered.count { it < setTotal } + 1, max(setTotal, 1))
                    val pct = if (correctCnt + wrongCnt == 0) 100 else correctCnt * 100 / (correctCnt + wrongCnt)
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Column(
                            Modifier.align(Alignment.CenterStart).clickable { toggleFlag(true) }.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("🔖", fontSize = 22.sp, modifier = Modifier.alpha(if ((cd?.first?.bookmark ?: 0) == 1) 1f else 0.4f))
                            Text("$shown of $setTotal", color = Color(0xFFD0D0D6), fontSize = 14.sp)
                        }
                        Column(
                            Modifier.align(Alignment.Center).clickable { showCardMenu = true }.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(Modifier.size(width = 34.dp, height = 24.dp).border(2.dp, grayText, RoundedCornerShape(5.dp)))
                        }
                        Column(
                            Modifier.align(Alignment.CenterEnd).padding(6.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            Text("Correct: $pct%", color = Color(0xFFD0D0D6), fontSize = 14.sp)
                            Text("Streak: $streak", color = Color(0xFFD0D0D6), fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        if (iconType != 0) {
            Box(
                Modifier.align(Alignment.Center).graphicsLayer {
                    alpha = iconAlpha.value
                    scaleX = iconScale.value
                    scaleY = iconScale.value
                }
            ) {
                Text(
                    text = when (iconType) {
                        1 -> "✓"
                        2 -> "✓✓"
                        else -> "✗"
                    },
                    color = if (iconType == 3) crossRed else tickGreen,
                    fontSize = 34.sp
                )
            }
        }

        if (!AppSettings.hintSeen && o != null && o.isNotEmpty() && !finishedNow) {
            Box(Modifier.fillMaxSize().background(Color(0xE8000000)).clickable { }, contentAlignment = Alignment.Center) {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("How to study", color = Color.White, fontSize = 26.sp)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Tap the card to turn it.\n\n⬆  Swipe UP: Easy  ✓✓\n⬅  Swipe LEFT: Good  ✓\n⬇  Swipe DOWN: Again  ✗\n➡  Swipe RIGHT: go back\n\n" +
                            "On a card with several faces, swipe up or left only turns it until you reach the last face.",
                        color = Color(0xFFD8D8DE), fontSize = 17.sp, textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(22.dp))
                    Button(onClick = { AppSettings.hintSeen = true; AppSettings.save(ctx) }) { Text("Got it") }
                }
            }
        }
    }

    zoom?.let { ZoomDialog(it) { zoom = null } }

    if (showCardMenu) AlertDialog(
        onDismissRequest = { showCardMenu = false },
        title = { Text("Card options") },
        text = {
            Column {
                TextButton(onClick = { showCardMenu = false; showSpeedDialog = true }) {
                    Text("⏱  Speed game", fontSize = 18.sp)
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showCardMenu = false; shuffleSet() }) {
                    Text("🔀  Shuffle cards", fontSize = 18.sp)
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showCardMenu = false; skipText = ""; showSkipDialog = true }) {
                    Text("↪  Skip to card", fontSize = 18.sp)
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { showCardMenu = false; showBrowseDialog = true }) {
                    Text("🎧  Browse mode", fontSize = 18.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { showCardMenu = false }) { Text("Cancel") } }
    )

    if (showSpeedDialog) AlertDialog(
        onDismissRequest = { showSpeedDialog = false },
        title = { Text("Speed game") },
        text = {
            Column {
                Text("Order:", color = grayText, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Random", "From start", "Serial").forEachIndexed { i, l ->
                        FilterChip(selected = speedMode == i, onClick = { speedMode = i }, label = { Text(l) })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Seconds per card:", color = grayText, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 15, 20, 30).forEach { s ->
                        FilterChip(selected = speedSeconds == s, onClick = { speedSeconds = s }, label = { Text("${s}s") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showSpeedDialog = false; startSpeedGame() }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("Cancel") } }
    )

    if (showSkipDialog) AlertDialog(
        onDismissRequest = { showSkipDialog = false; skipText = "" },
        title = { Text("Skip to card") },
        text = {
            Column {
                Text("Enter a card number (1 to $setTotal)", color = grayText, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = skipText,
                    onValueChange = { v -> skipText = v.filter { it.isDigit() }.take(6) },
                    singleLine = true,
                    label = { Text("Card number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val n = skipText.toIntOrNull()
                val ord = order
                if (n != null && ord != null && n in 1..ord.size) {
                    pos = n - 1
                    face = 0
                    persist()
                }
                showSkipDialog = false
                skipText = ""
            }) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = { showSkipDialog = false; skipText = "" }) { Text("Cancel") } }
    )

    if (showBrowseDialog) AlertDialog(
        onDismissRequest = { showBrowseDialog = false },
        title = { Text("Browse mode") },
        text = {
            Column {
                Text("Order:", color = grayText, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Random", "From start", "Serial").forEachIndexed { i, l ->
                        FilterChip(selected = browseMode == i, onClick = { browseMode = i }, label = { Text(l) })
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Seconds per phase (question / answer):", color = grayText, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3, 5, 8, 12, 20).forEach { s ->
                        FilterChip(selected = browseDelay == s, onClick = { browseDelay = s }, label = { Text("${s}s") })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Cards will auto-reveal the answer and auto-advance. Any audio on each face will play automatically.", color = grayText, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { showBrowseDialog = false; startBrowseMode() }) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = { showBrowseDialog = false }) { Text("Cancel") } }
    )

    resumeAsk?.let { sv ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Continue where you left off?") },
            text = {
                Column {
                    Text("You were on card ${min(sv.answered.count { it < sv.total } + 1, max(sv.total, 1))} of ${sv.total}.")
                    Row(Modifier.clickable { rememberChoice = !rememberChoice }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = rememberChoice, onCheckedChange = { rememberChoice = it })
                        Text("Remember my choice for this deck")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (rememberChoice) Resume.setChoice(ctx, deckId, 1)
                    resumeAsk = null
                    applySaved(sv)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (rememberChoice) Resume.setChoice(ctx, deckId, 2)
                    resumeAsk = null
                    scope.launch { startFresh() }
                }) { Text("Start over") }
            }
        )
    }

    if (showSettings) Dialog(onDismissRequest = { showSettings = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(12.dp).fillMaxWidth().fillMaxHeight(0.9f)) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showSettings = false }) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    SettingsContent()
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}
