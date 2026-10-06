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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
    data class DeckS(val id: Long) : Screen()
    data class Edit(val deckId: Long, val cardId: Long) : Screen()
    data class Review(val deckId: Long) : Screen()
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
    val pop = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(stack.size > 1) { pop() }
    when (val cur = stack.last()) {
        is Screen.Home -> HomeScreen(
            db, theme, onTheme,
            { stack.add(Screen.DeckS(it)) },
            { d, c -> stack.add(Screen.Edit(d, c)) },
            { d, t -> stack.add(Screen.Import(d, t)) }
        )
        is Screen.DeckS -> DeckScreen(
            db, cur.id, { pop() },
            { stack.add(Screen.Edit(cur.id, it)) },
            { stack.add(Screen.Review(cur.id)) },
            { t -> stack.add(Screen.Import(cur.id, t)) }
        )
        is Screen.Edit -> EditorScreen(db, cur.deckId, cur.cardId, dark) { pop() }
        is Screen.Review -> ReviewScreen(db, cur.deckId, dark) { pop() }
        is Screen.Import -> ImportScreen(db, cur.deckId, cur.text) { pop() }
    }
}

// ---------- home ----------

@Composable
fun HomeScreen(
    db: Db, theme: Int, onTheme: (Int) -> Unit,
    open: (Long) -> Unit, editCard: (Long, Long) -> Unit, importText: (Long, String) -> Unit
) {
    val dao = db.dao()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember { dao.decks(System.currentTimeMillis()) }
    val decks by flow.collectAsState(emptyList())
    var q by remember { mutableStateOf("") }
    val hitFlow = remember(q) { dao.searchAll(q.trim()) }
    val hits by hitFlow.collectAsState(emptyList())
    var dialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val shown = decks.filter { it.name.contains(q.trim(), ignoreCase = true) }

    val exportL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            val msg = try { Backup.export(ctx, db, uri) } catch (e: Exception) { "Export failed: ${e.message}" }
            toast(ctx, msg)
        }
    }
    val importL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val msg = try { Backup.restore(ctx, db, uri) } catch (e: Exception) { "Import failed: this is not a valid backup file" }
            toast(ctx, msg)
        }
    }
    val tsvFileL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val t = readUriText(ctx, uri)
            if (t.isNullOrBlank()) toast(ctx, "Could not read that file") else importText(0L, t)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("My Decks") }, actions = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Import TSV file") }, onClick = { menu = false; tsvFileL.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text("Copy TSV template") }, onClick = { menu = false; copyToClipboard(ctx, Tsv.TEMPLATE); toast(ctx, "Template copied") })
                        DropdownMenuItem(text = { Text("Export backup (Drive/OneDrive)") }, onClick = { menu = false; exportL.launch("flashcards-backup.zip") })
                        DropdownMenuItem(text = { Text("Import backup") }, onClick = { menu = false; importL.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text(listOf("Theme: System", "Theme: Light", "Theme: Dark")[theme]) }, onClick = { onTheme((theme + 1) % 3) })
                    }
                }
            })
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { name = ""; dialog = true }) { Text("+ Create Deck") } }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            OutlinedTextField(q, { q = it }, label = { Text("Search decks and cards") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val t = clipboardText(ctx)
                    if (t.isNullOrBlank()) toast(ctx, "Clipboard is empty. Copy the TSV from your AI first.") else importText(0L, t)
                }) { Text("Import from Clipboard") }
            }
            if (shown.isEmpty() && hits.isEmpty()) Text("No decks yet. Tap + Create Deck.", Modifier.padding(24.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(shown, key = { "d${it.id}" }) { d ->
                    Card(onClick = { open(d.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(d.name, style = MaterialTheme.typography.titleLarge)
                            Text("${d.total} cards     ${d.due + d.fresh} to study  (${d.fresh} new, ${d.due} due)")
                        }
                    }
                }
                if (hits.isNotEmpty()) {
                    item { Text("Matching cards", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                    items(hits, key = { "c${it.id}" }) { h ->
                        Card(onClick = { editCard(h.deckId, h.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text((h.preview ?: "(image / voice)").take(120), maxLines = 2)
                                Text(h.deckName + (if (h.tags.isNotBlank()) "   " + h.tags else ""), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
    if (dialog) AlertDialog(
        onDismissRequest = { dialog = false },
        title = { Text("New deck") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) scope.launch { dao.insertDeck(Deck(name = name.trim())) }
                dialog = false
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = { dialog = false }) { Text("Cancel") } }
    )
}

// ---------- deck ----------

@Composable
fun DeckScreen(
    db: Db, deckId: Long, back: () -> Unit, edit: (Long) -> Unit, study: () -> Unit, importText: (String) -> Unit
) {
    val dao = db.dao()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val infoFlow = remember { dao.decks(System.currentTimeMillis()) }
    val infos by infoFlow.collectAsState(emptyList())
    val info = infos.firstOrNull { it.id == deckId }
    var q by remember { mutableStateOf("") }
    val cardFlow = remember(q) { dao.cards(deckId, q.trim()) }
    val cards by cardFlow.collectAsState(emptyList())
    val tagFlow = remember { dao.tagLists(deckId) }
    val tagLists by tagFlow.collectAsState(emptyList())
    val allTags = remember(tagLists) {
        tagLists.flatMap { it.split(Regex("\\s+")) }.filter { it.isNotBlank() }.distinct().sorted()
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    val tsvFileL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val t = readUriText(ctx, uri)
            if (t.isNullOrBlank()) toast(ctx, "Could not read that file") else importText(t)
        }
    }
    val exportTsvL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/tab-separated-values")) { uri ->
        if (uri != null) scope.launch {
            val ok = try { writeUriText(ctx, uri, Tsv.deckTsv(dao, deckId)) } catch (e: Exception) { false }
            toast(ctx, if (ok) "TSV exported (text and formulas only; use backup for images and audio)" else "Export failed")
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { },
            navigationIcon = { BackIcon(back) },
            actions = {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename deck") }, onClick = { menu = false; renameText = info?.name ?: ""; renaming = true })
                        DropdownMenuItem(text = { Text("Import TSV from clipboard") }, onClick = {
                            menu = false
                            val t = clipboardText(ctx)
                            if (t.isNullOrBlank()) toast(ctx, "Clipboard is empty") else importText(t)
                        })
                        DropdownMenuItem(text = { Text("Import TSV file") }, onClick = { menu = false; tsvFileL.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text("Export TSV file") }, onClick = { menu = false; exportTsvL.launch("deck.tsv") })
                        DropdownMenuItem(text = { Text("Copy TSV to clipboard") }, onClick = {
                            menu = false
                            scope.launch { copyToClipboard(ctx, Tsv.deckTsv(dao, deckId)); toast(ctx, "Copied") }
                        })
                        DropdownMenuItem(text = { Text("Copy TSV template") }, onClick = { menu = false; copyToClipboard(ctx, Tsv.TEMPLATE); toast(ctx, "Template copied") })
                    }
                }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete deck") }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Text("${info?.total ?: 0} cards  |  ${info?.fresh ?: 0} new  |  ${info?.due ?: 0} due")
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = study, enabled = (info?.let { it.fresh + it.due } ?: 0) > 0) { Text("Study") }
                OutlinedButton(onClick = { edit(0L) }) { Text("+ Add card") }
            }
            OutlinedTextField(q, { q = it }, label = { Text("Search cards or tags") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (allTags.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    allTags.forEach { t ->
                        FilterChip(selected = q.trim() == t, onClick = { q = if (q.trim() == t) "" else t }, label = { Text(t) })
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (cards.isEmpty()) Text("No cards.", Modifier.padding(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(cards, key = { it.id }) { c ->
                    Card(onClick = { edit(c.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text((c.preview ?: "(image / voice)").take(120), maxLines = 2)
                            if (c.tags.isNotBlank()) Text(c.tags, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    if (renaming) AlertDialog(
        onDismissRequest = { renaming = false },
        title = { Text("Rename deck") },
        text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                if (renameText.isNotBlank()) scope.launch { dao.renameDeck(deckId, renameText.trim()) }
                renaming = false
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this deck and all its cards?") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch { dao.deleteDeckItems(deckId); dao.deleteDeckCards(deckId); dao.deleteDeck(deckId); back() }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
}

// ---------- editor ----------

private val formulaButtons = listOf(
    "x²" to "^{2}", "xₙ" to "_{n}", "√" to "\\sqrt{x}", "a/b" to "\\frac{a}{b}",
    "∫" to "\\int_{a}^{b} f(x)\\,dx", "Σ" to "\\sum_{i=1}^{n} i", "π" to "\\pi", "α" to "\\alpha",
    "β" to "\\beta", "θ" to "\\theta", "Δ" to "\\Delta", "vec" to "\\vec{F}", "lim" to "\\lim_{x\\to 0}",
    "d/dx" to "\\frac{d}{dx}", "×10ⁿ" to "\\times 10^{n}", "matrix" to "\\begin{bmatrix} a & b \\\\ c & d \\end{bmatrix}"
)

@Composable
fun EditorScreen(db: Db, deckId: Long, cardId: Long, dark: Boolean, back: () -> Unit) {
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

    LaunchedEffect(cardId, reload) {
        var newFaces: List<List<EItem>> = emptyList()
        var newTags = ""
        if (cardId != 0L) {
            val c = dao.card(cardId)
            val its = dao.items(cardId)
            newTags = c?.tags ?: ""
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
                cid = dao.insertCard(Flashcard(deckId = deckId, tags = tg))
            } else {
                dao.card(cardId)?.let { dao.updateCard(it.copy(tags = tg)) }
                dao.deleteItems(cardId)
                cid = cardId
            }
            dao.insertItems(clean.flatMapIndexed { fi, f ->
                f.mapIndexed { pi, e -> Item(cardId = cid, face = fi, pos = pi, type = e.type, data = e.data) }
            })
            back()
        }
    }

    fun insertFormula(t: String) {
        val cur = faces[curIdx()].toMutableList()
        val target = if (focusIdx in cur.indices && cur[focusIdx].type == "LATEX") focusIdx else cur.indexOfLast { it.type == "LATEX" }
        if (target >= 0) cur[target] = cur[target].copy(data = cur[target].data + t) else cur.add(EItem("LATEX", t))
        setFace(cur)
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
                if (cardId != 0L) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete card") }
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
                            label = { Text(if (e.type == "LATEX") "Formula (LaTeX)" else "Text") },
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
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                formulaButtons.forEach { (label, tpl) -> FilledTonalButton(onClick = { insertFormula(tpl) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(label) } }
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
fun ReviewScreen(db: Db, deckId: Long, dark: Boolean, back: () -> Unit) {
    val dao = db.dao()
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<List<Long>?>(null) }
    var card by remember { mutableStateOf<Flashcard?>(null) }
    var faceList by remember { mutableStateOf<List<List<Item>>>(emptyList()) }
    var face by remember { mutableStateOf(0) }
    var step by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf(0) }
    var flipping by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf<String?>(null) }
    val rot = remember { Animatable(0f) }
    LaunchedEffect(Unit) { queue = dao.queue(deckId, System.currentTimeMillis()) }
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
            rot.animateTo(90f, tween(140))
            face++
            rot.snapTo(-90f)
            rot.animateTo(0f, tween(140))
            flipping = false
        }
    }
    fun rate(r: Int) {
        val c = card ?: return
        if (q == null || c.id != curId) return
        val now = System.currentTimeMillis()
        val n = Scheduler.next(c, r, now)
        scope.launch {
            dao.updateCard(n)
            dao.log(ReviewLog(cardId = c.id, time = now, rating = r, interval = n.interval))
        }
        queue = q.drop(1) + (if (r == 0) listOf(c.id) else emptyList())
        done++
        step++
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Study  ($done done)") },
            navigationIcon = { IconButton(onClick = back) { Icon(Icons.Default.Close, contentDescription = "Close") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                q == null -> Text("Loading...")
                q.isEmpty() -> {
                    Spacer(Modifier.height(48.dp))
                    Text("All done for now! 🎉", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = back) { Text("Back to deck") }
                }
                card?.id != curId || faceList.isEmpty() -> Text("Loading...")
                else -> {
                    val fi = face.coerceIn(0, faceList.lastIndex)
                    val last = fi >= faceList.lastIndex
                    val faceItems = faceList[fi]
                    val textItems = faceItems.filter { it.type == "TEXT" || it.type == "LATEX" }
                    val imgs = faceItems.filter { it.type == "IMAGE" }
                    val auds = faceItems.filter { it.type == "AUDIO" }
                    Text("FACE ${fi + 1} / ${faceList.size}", style = MaterialTheme.typography.labelLarge)
                    Box(
                        Modifier.weight(1f).fillMaxWidth().graphicsLayer {
                            rotationY = rot.value
                            cameraDistance = 12f * density
                        }
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            if (textItems.isNotEmpty() || imgs.isEmpty()) {
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    FaceView(textItems.map { it.type to it.data }, dark, Modifier.fillMaxSize())
                                    Box(Modifier.matchParentSize().pointerInput(fi, faceList.size) {
                                        detectTapGestures { advance() }
                                    })
                                }
                            }
                            if (imgs.isNotEmpty()) {
                                Column(
                                    Modifier.then(if (textItems.isEmpty()) Modifier.weight(1f) else Modifier.heightIn(max = 220.dp))
                                        .fillMaxWidth().verticalScroll(rememberScrollState())
                                ) {
                                    imgs.forEach { im -> ImageThumb(im.data, Modifier.fillMaxWidth().padding(4.dp), onClick = { zoom = im.data }) }
                                }
                            }
                        }
                    }
                    auds.forEach { AudioPlayButton(it.data) }
                    if (!last) {
                        Button(onClick = { advance() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Show next face  (or tap the card)") }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
