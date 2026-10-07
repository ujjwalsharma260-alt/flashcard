@file:OptIn(ExperimentalMaterial3Api::class)

package com.flashcards.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlin.math.max
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Screen {
    object Home : Screen()
    data class DeckS(val id: Long, val q: String = "") : Screen()
    data class Edit(val deckId: Long, val cardId: Long, val ink: Boolean = false) : Screen()
    data class Review(val deckId: Long, val mode: Int = 0) : Screen()
    object Settings : Screen()
    object Help : Screen()
    data class Import(val deckId: Long, val text: String) : Screen()
}

data class EItem(val type: String, val data: String)

// ---------- helpers ----------

fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

fun clipboardText(ctx: Context): String? {
    return try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(ctx)?.toString()
    } catch (e: Exception) {
        null
    }
}

fun copyToClipboard(ctx: Context, text: String) {
    try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("flashcards", text))
    } catch (e: Exception) {
        toast(ctx, "Could not copy")
    }
}

suspend fun readUriText(ctx: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }
}

suspend fun writeUriText(ctx: Context, uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
    try {
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        true
    } catch (e: Exception) {
        false
    }
}

fun normTags(s: String): String =
    s.split(Regex("[\\s,]+")).filter { it.isNotBlank() }.map { if (it.startsWith("#")) it else "#$it" }.distinct().joinToString(" ")

@Composable
fun BackIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
}

// ---------- root ----------

@Composable
fun AppRoot(db: Db, dark: Boolean, theme: Int, onTheme: (Int) -> Unit) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val holder = rememberSaveableStateHolder()
    fun keyOf(i: Int, sc: Screen): String = "$i|" + (if (sc is Screen.Import) "import" else sc.toString())
    val pop = {
        if (stack.size > 1) {
            holder.removeState(keyOf(stack.lastIndex, stack.last()))
            stack.removeAt(stack.lastIndex)
        }
    }
    BackHandler(stack.size > 1) { pop() }
    val cur = stack.last()
    // keeps scroll position and search text of screens underneath (e.g. the deck list) while you edit a card
    holder.SaveableStateProvider(keyOf(stack.lastIndex, cur)) {
        when (cur) {
            is Screen.Home -> HomeScreen(
                db, theme, onTheme,
                { stack.add(Screen.DeckS(it)) },
                { q -> stack.add(Screen.DeckS(0L, q)) },
                { d, c -> stack.add(Screen.Edit(d, c)) },
                { d, t -> stack.add(Screen.Import(d, t)) },
                { stack.add(Screen.Settings) },
                { stack.add(Screen.Help) }
            )
            is Screen.DeckS -> DeckScreen(
                db, cur.id, cur.q, { pop() },
                { d, c -> stack.add(Screen.Edit(d, c)) },
                { d -> stack.add(Screen.Edit(d, 0L, true)) },
                { m -> stack.add(Screen.Review(cur.id, m)) },
                { t -> stack.add(Screen.Import(cur.id, t)) }
            )
            is Screen.Edit -> EditorScreen(db, cur.deckId, cur.cardId, cur.ink, dark) { pop() }
            is Screen.Review -> ReviewScreen(db, cur.deckId, cur.mode) { pop() }
            is Screen.Import -> ImportScreen(db, cur.deckId, cur.text) { pop() }
            is Screen.Settings -> SettingsScreen { pop() }
            is Screen.Help -> HelpScreen { pop() }
        }
    }
}

// ---------- editor ----------

@Composable
fun EditorScreen(db: Db, deckId: Long, cardId: Long, ink: Boolean, dark: Boolean, back: () -> Unit) {
    val dao = db.dao()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = "d_${deckId}_${cardId}"
    val faces = remember { mutableStateListOf(listOf(EItem("TEXT", "")), listOf(EItem("TEXT", ""))) }
    var tags by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf(0) }
    var focusIdx by remember { mutableStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var zoom by remember { mutableStateOf<String?>(null) }
    var fav by remember { mutableStateOf(false) }
    var susp by remember { mutableStateOf(false) }
    var bookmark by remember { mutableStateOf(false) }
    var editMenu by remember { mutableStateOf(false) }
    var inkEdit by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(cardId, reload) {
        var newFaces: List<List<EItem>> = emptyList()
        var newTags = ""
        if (cardId != 0L) {
            val c = dao.card(cardId)
            val its = dao.items(cardId)
            newTags = c?.tags ?: ""
            fav = (c?.fav ?: 0) == 1
            susp = (c?.suspended ?: 0) == 1
            bookmark = (c?.bookmark ?: 0) == 1
            newFaces = its.groupBy { it.face }.toSortedMap().values.map { l -> l.map { EItem(it.type, it.data) } }
        }
        if (reload == 0) {
            val d = Drafts.load(ctx, key)
            if (d != null && d.second.isNotEmpty()) {
                newTags = d.first
                newFaces = d.second
                restored = true
                dirty = true
            }
        }
        if (cardId == 0L && ink && newFaces.isEmpty()) newFaces = listOf(listOf(EItem("INK", "")), listOf(EItem("INK", "")))
        val list = ArrayList<List<EItem>>(newFaces.take(5))
        while (list.size < 2) list.add(listOf(EItem("TEXT", "")))
        faces.clear()
        faces.addAll(list)
        tags = newTags
        sel = 0
    }

    val snap = faces.toList()
    LaunchedEffect(snap, tags, dirty) {
        if (dirty) {
            delay(500)
            Drafts.save(ctx, key, tags, snap)
        }
    }

    fun curIdx(): Int = sel.coerceIn(0, faces.lastIndex)
    fun setFace(items: List<EItem>) { faces[curIdx()] = items; dirty = true }

    fun save() {
        val clean = faces.map { f -> f.filter { it.data.isNotBlank() } }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) { err = "Add some content first."; return }
        dirty = false
        Drafts.clear(ctx, key)
        val tg = normTags(tags)
        scope.launch {
            val cid: Long
            if (cardId == 0L) {
                cid = dao.insertCard(Flashcard(deckId = deckId, tags = tg, fav = if (fav) 1 else 0, suspended = if (susp) 1 else 0, bookmark = if (bookmark) 1 else 0))
            } else {
                dao.card(cardId)?.let { dao.updateCard(it.copy(tags = tg, fav = if (fav) 1 else 0, suspended = if (susp) 1 else 0, bookmark = if (bookmark) 1 else 0)) }
                dao.deleteItems(cardId)
                cid = cardId
            }
            dao.insertItems(clean.flatMapIndexed { fi, f ->
                f.mapIndexed { pi, e -> Item(cardId = cid, face = fi, pos = pi, type = e.type, data = e.data) }
            })
            back()
        }
    }

    fun addImage(uri: Uri) {
        scope.launch {
            val n = withContext(Dispatchers.IO) { ImageStore.saveFromUri(ctx, uri) }
            if (n == null) toast(ctx, "Could not read that image") else setFace(faces[curIdx()] + EItem("IMAGE", n))
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) addImage(uri)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (cardId == 0L) "New card" else "Edit card") },
            navigationIcon = { BackIcon(back) },
            actions = {
                IconButton(onClick = {
                    val nv = !bookmark
                    bookmark = nv
                    if (cardId != 0L) scope.launch { dao.card(cardId)?.let { dao.updateCard(it.copy(bookmark = if (nv) 1 else 0)) } }
                }) {
                    Text("🔖", modifier = Modifier.alpha(if (bookmark) 1f else 0.35f))
                }
                IconButton(onClick = {
                    val nv = !fav
                    fav = nv
                    if (cardId != 0L) scope.launch { dao.card(cardId)?.let { dao.updateCard(it.copy(fav = if (nv) 1 else 0)) } }
                }) {
                    Icon(Icons.Default.Star, contentDescription = "Favorite", tint = if (fav) Color(0xFFFFB300) else LocalContentColor.current)
                }
                Box {
                    IconButton(onClick = { editMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = editMenu, onDismissRequest = { editMenu = false }) {
                        DropdownMenuItem(text = { Text(if (susp) "Unsuspend card" else "Suspend card") }, onClick = {
                            editMenu = false
                            val nv = !susp
                            susp = nv
                            if (cardId != 0L) scope.launch { dao.card(cardId)?.let { dao.updateCard(it.copy(suspended = if (nv) 1 else 0)) } }
                        })
                        if (cardId != 0L) {
                            DropdownMenuItem(text = { Text("Duplicate card") }, onClick = {
                                editMenu = false
                                scope.launch {
                                    val n = try { Bulk.duplicateCards(ctx, db, listOf(cardId)) } catch (e: Exception) { 0 }
                                    toast(ctx, if (n > 0) "Card duplicated (saved version)" else "Duplicate failed")
                                }
                            })
                            DropdownMenuItem(text = { Text("Delete card") }, onClick = { editMenu = false; confirmDelete = true })
                        }
                    }
                }
                IconButton(onClick = { save() }) { Icon(Icons.Default.Check, contentDescription = "Save") }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
            if (restored) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Unsaved draft restored", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = {
                        Drafts.clear(ctx, key)
                        dirty = false
                        restored = false
                        reload++
                    }) { Text("Discard draft") }
                }
            }
            ScrollableTabRow(selectedTabIndex = curIdx(), edgePadding = 0.dp) {
                faces.forEachIndexed { i, _ -> Tab(selected = curIdx() == i, onClick = { sel = i }, text = { Text("Face ${i + 1}") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = faces.size < 5, onClick = {
                    if (faces.size < 5) { faces.add(listOf(EItem("TEXT", ""))); sel = faces.lastIndex; dirty = true }
                }) { Text("+ Face") }
                TextButton(enabled = faces.size > 2, onClick = {
                    if (faces.size > 2) {
                        val s = curIdx()
                        faces.removeAt(s)
                        sel = s.coerceAtMost(faces.lastIndex)
                        dirty = true
                    }
                }) { Text("Remove face") }
            }
            val cur = faces[curIdx()]
            cur.forEachIndexed { i, e ->
                if (e.type == "AUDIO") {
                    AudioItem(
                        name = e.data,
                        onChange = { nv -> setFace(faces[curIdx()].toMutableList().also { l -> if (i in l.indices) l[i] = EItem("AUDIO", nv) }) },
                        onRemove = { setFace(faces[curIdx()].toMutableList().also { l -> if (i in l.indices) l.removeAt(i) }) }
                    )
                } else if (e.type == "INK") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).padding(vertical = 4.dp).clickable { inkEdit = i }) {
                            InkView(e.data, Modifier.fillMaxWidth().heightIn(max = 400.dp), placeholder = true)
                        }
                        IconButton(onClick = { setFace(faces[curIdx()].toMutableList().also { l -> if (i in l.indices) l.removeAt(i) }) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove handwriting")
                        }
                    }
                } else if (e.type == "IMAGE") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ImageThumb(e.data, Modifier.weight(1f).heightIn(max = 160.dp).padding(vertical = 4.dp), onClick = { zoom = e.data })
                        IconButton(onClick = { setFace(faces[curIdx()].toMutableList().also { l -> if (i in l.indices) l.removeAt(i) }) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove image")
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = e.data,
                            onValueChange = { nv -> setFace(faces[curIdx()].toMutableList().also { l -> if (i in l.indices) l[i] = e.copy(data = nv) }) },
                            label = { Text(if (e.type == "LATEX") "Formula: paste LaTeX here" else "Text (pasted LaTeX also shows as maths)") },
                            textStyle = if (e.type == "LATEX") MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(vertical = 4.dp).onFocusChanged { if (it.isFocused) focusIdx = i }
                        )
                        IconButton(onClick = { setFace(faces[curIdx()].toMutableList().also { l -> if (i in l.indices) l.removeAt(i) }) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove")
                        }
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { setFace(faces[curIdx()] + EItem("TEXT", "")) }) { Text("+ Text") }
                OutlinedButton(onClick = { setFace(faces[curIdx()] + EItem("LATEX", "")) }) { Text("+ Formula") }
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("+ Image") }
                OutlinedButton(onClick = {
                    val u = ImageStore.clipboardImageUri(ctx)
                    if (u == null) toast(ctx, "No image on the clipboard. Copy a screenshot first, or use + Image.") else addImage(u)
                }) { Text("Paste image") }
                OutlinedButton(onClick = { setFace(faces[curIdx()] + EItem("AUDIO", "")) }) { Text("+ Audio") }
                OutlinedButton(onClick = {
                    val l = faces[curIdx()]
                    setFace(l + EItem("INK", ""))
                    inkEdit = l.size
                }) { Text("✍ Handwriting") }
            }
            Text("Preview", style = MaterialTheme.typography.labelMedium)
            FaceView(
                cur.filter { (it.type == "TEXT" || it.type == "LATEX") && it.data.isNotBlank() }.map { it.type to it.data },
                dark, Modifier.fillMaxWidth().height(180.dp)
            )
            OutlinedTextField(tags, { tags = it; dirty = true }, label = { Text("Tags (e.g. #physics #mechanics)") }, modifier = Modifier.fillMaxWidth())
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(32.dp))
        }
    }
    zoom?.let { ZoomDialog(it) { zoom = null } }
    inkEdit?.let { idx ->
        val items = faces[curIdx()]
        if (idx in items.indices) {
            InkEditorDialog(
                initial = items[idx].data,
                onDone = { nv ->
                    setFace(faces[curIdx()].toMutableList().also { l -> if (idx in l.indices) l[idx] = EItem("INK", nv) })
                    inkEdit = null
                },
                onCancel = { inkEdit = null }
            )
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this card?") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                dirty = false
                Drafts.clear(ctx, key)
                scope.launch { dao.deleteItems(cardId); dao.deleteCard(cardId); back() }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
}

// ---------- review ----------

@Composable
fun ReviewScreen(db: Db, deckId: Long, mode: Int, back: () -> Unit) {
    val dao = db.dao()
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<List<Long>?>(null) }
    var setNo by remember { mutableStateOf(1) }
    var setTotal by remember { mutableStateOf(0) }
    var missed by remember { mutableStateOf(setOf<Long>()) }
    var card by remember { mutableStateOf<Flashcard?>(null) }
    var faceList by remember { mutableStateOf<List<List<Item>>>(emptyList()) }
    var face by remember { mutableStateOf(0) }
    var step by remember { mutableStateOf(0) }
    var flipping by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf<String?>(null) }
    val rot = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(setNo) {
        queue = null
        val q0 = Session.build(dao, deckId, mode, System.currentTimeMillis())
        missed = emptySet()
        setTotal = q0.size
        queue = q0
    }
    val q = queue
    val curId = q?.firstOrNull()
    LaunchedEffect(curId, step) {
        if (curId != null) {
            card = dao.card(curId)
            faceList = dao.items(curId).groupBy { it.face }.toSortedMap().values.toList()
            face = 0
        }
    }
    fun advance() {
        if (flipping || face >= faceList.lastIndex) return
        flipping = true
        scope.launch {
            when (AppSettings.flipStyle) {
                2 -> { face++ }
                1 -> {
                    rot.animateTo(90f, tween(150))
                    face++
                    rot.snapTo(-90f)
                    rot.animateTo(0f, tween(150))
                }
                else -> {
                    fade.animateTo(0f, tween(90))
                    face++
                    fade.animateTo(1f, tween(130))
                }
            }
            flipping = false
        }
    }
    fun rate(r: Int) {
        val c = card ?: return
        if (q == null || c.id != curId) return
        val now = System.currentTimeMillis()
        val onSchedule = c.state == 0 || c.due <= now
        val n = if (onSchedule) {
            Scheduler.next(c, r, now, if (AppSettings.streakBonus) AppSettings.streakN else 0, AppSettings.streakMult.toDouble())
        } else {
            Scheduler.statsOnly(c, r, now)   // studied early (e.g. a Bookmarked session): only count right/wrong
        }
        scope.launch {
            dao.updateCard(n)
            dao.log(ReviewLog(cardId = c.id, time = now, rating = r, interval = n.interval))
        }
        if (r == 0) missed = missed + c.id
        val again = r == 0 && AppSettings.retryMissed
        queue = q.drop(1) + (if (again) listOf(c.id) else emptyList())
        step++
    }

    val title = if (mode == 0) "Set $setNo" else listOf("", "Bookmarked", "Favorites", "Weak cards", "Missed today")[mode.coerceIn(0, 4)]
    val cardShape = RoundedCornerShape(28.dp)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.Default.Close, contentDescription = "Close") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                q == null -> Text("Loading...")
                q.isEmpty() -> {
                    Spacer(Modifier.height(48.dp))
                    if (setTotal == 0) {
                        Text("Nothing to study right now 🎉", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = back) { Text("Back to deck") }
                    } else {
                        Text("Set $setNo complete! 🎉", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text("You studied $setTotal cards. Missed at first: ${missed.size}")
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { setNo++ }) { Text("Next set") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = back) { Text("Finish") }
                    }
                }
                card?.id != curId || faceList.isEmpty() -> Text("Loading...")
                else -> {
                    val fi = face.coerceIn(0, faceList.lastIndex)
                    val last = fi >= faceList.lastIndex
                    val faceItems = faceList[fi]
                    val textItems = faceItems.filter { it.type == "TEXT" || it.type == "LATEX" }
                    val imgs = faceItems.filter { it.type == "IMAGE" }
                    val inks = faceItems.filter { it.type == "INK" }
                    val auds = faceItems.filter { it.type == "AUDIO" }
                    val hasText = textItems.isNotEmpty()
                    val visuals = imgs.isNotEmpty() || inks.isNotEmpty()
                    val left = q.distinct().size
                    Text("$left cards left   ·   missed so far: ${missed.size}   ·   face ${fi + 1}/${faceList.size}", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(
                        progress = { (1f - left.toFloat() / max(setTotal, 1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    )
                    Box(
                        Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp)
                            .graphicsLayer {
                                rotationY = rot.value
                                alpha = fade.value
                                cameraDistance = 12f * density
                                scaleX = 0.96f + 0.04f * fade.value
                                scaleY = 0.96f + 0.04f * fade.value
                            }
                            .clip(cardShape)
                            .background(Color.Black)
                            .border(2.dp, Color(0xFF5C5C66), cardShape)
                    ) {
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.then(if (hasText || !visuals) Modifier.weight(1f) else Modifier.height(1.dp)).fillMaxWidth()) {
                                FaceView(textItems.map { it.type to it.data }, true, Modifier.fillMaxSize())
                                Box(Modifier.matchParentSize().pointerInput(fi, faceList.size) {
                                    detectTapGestures { advance() }
                                })
                            }
                            if (visuals) {
                                if (!hasText && imgs.isEmpty() && inks.size == 1) {
                                    InkView(
                                        inks[0].data,
                                        Modifier.weight(1f).fillMaxWidth().pointerInput(fi) { detectTapGestures { advance() } },
                                        fit = true, color = Color.White
                                    )
                                } else {
                                    Box(
                                        Modifier.then(if (hasText) Modifier.heightIn(max = 240.dp) else Modifier.weight(1f)).fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(Modifier.verticalScroll(rememberScrollState())) {
                                            imgs.forEach { im -> ImageThumb(im.data, Modifier.fillMaxWidth().padding(4.dp), onClick = { zoom = im.data }) }
                                            inks.forEach { ik ->
                                                InkView(
                                                    ik.data,
                                                    Modifier.fillMaxWidth().padding(4.dp).pointerInput(fi) { detectTapGestures { advance() } },
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    auds.forEach { AudioPlayButton(it.data) }
                    if (!last) {
                        Button(onClick = { advance() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Show next face  (or tap the card)") }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Again", "Hard", "Good", "Easy").forEachIndexed { i, l ->
                                Button(onClick = { rate(i) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(2.dp)) { Text(l) }
                            }
                        }
                    }
                }
            }
        }
    }
    zoom?.let { ZoomDialog(it) { zoom = null } }
}
