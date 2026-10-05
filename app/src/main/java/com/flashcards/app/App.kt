@file:OptIn(ExperimentalMaterial3Api::class)

package com.flashcards.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

sealed class Screen {
    object Home : Screen()
    data class DeckS(val id: Long) : Screen()
    data class Edit(val deckId: Long, val cardId: Long) : Screen()
    data class Review(val deckId: Long) : Screen()
}

@Composable
fun AppRoot(db: Db, dark: Boolean, theme: Int, onTheme: (Int) -> Unit) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val pop = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(stack.size > 1) { pop() }
    when (val cur = stack.last()) {
        is Screen.Home -> HomeScreen(db, theme, onTheme) { stack.add(Screen.DeckS(it)) }
        is Screen.DeckS -> DeckScreen(db, cur.id, { pop() }, { stack.add(Screen.Edit(cur.id, it)) }, { stack.add(Screen.Review(cur.id)) })
        is Screen.Edit -> EditorScreen(db, cur.deckId, cur.cardId, dark) { pop() }
        is Screen.Review -> ReviewScreen(db, cur.deckId, dark) { pop() }
    }
}

@Composable
fun HomeScreen(db: Db, theme: Int, onTheme: (Int) -> Unit, open: (Long) -> Unit) {
    val dao = db.dao()
    val scope = rememberCoroutineScope()
    val flow = remember { dao.decks(System.currentTimeMillis()) }
    val decks by flow.collectAsState(emptyList())
    var q by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val shown = decks.filter { it.name.contains(q, ignoreCase = true) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("My Decks") }, actions = {
                TextButton(onClick = { onTheme((theme + 1) % 3) }) { Text(listOf("Theme: System", "Theme: Light", "Theme: Dark")[theme]) }
            })
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { name = ""; dialog = true }) { Text("+ Create Deck") } }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            OutlinedTextField(q, { q = it }, label = { Text("Search decks") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            if (shown.isEmpty()) Text("No decks yet. Tap + Create Deck.", Modifier.padding(24.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(shown, key = { it.id }) { d ->
                    Card(onClick = { open(d.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(d.name, style = MaterialTheme.typography.titleLarge)
                            Text("${d.total} cards     ${d.due + d.fresh} to study  (${d.fresh} new, ${d.due} due)")
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

@Composable
fun DeckScreen(db: Db, deckId: Long, back: () -> Unit, edit: (Long) -> Unit, study: () -> Unit) {
    val dao = db.dao()
    val scope = rememberCoroutineScope()
    val infoFlow = remember { dao.decks(System.currentTimeMillis()) }
    val infos by infoFlow.collectAsState(emptyList())
    val info = infos.firstOrNull { it.id == deckId }
    var q by remember { mutableStateOf("") }
    val cardFlow = remember(q) { dao.cards(deckId, q.trim()) }
    val cards by cardFlow.collectAsState(emptyList())
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(info?.name ?: "") },
            navigationIcon = { TextButton(onClick = back) { Text("Back") } },
            actions = { TextButton(onClick = { confirmDelete = true }) { Text("Delete deck") } }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Text("${info?.total ?: 0} cards  |  ${info?.fresh ?: 0} new  |  ${info?.due ?: 0} due")
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = study, enabled = (info?.let { it.fresh + it.due } ?: 0) > 0) { Text("Study") }
                OutlinedButton(onClick = { edit(0L) }) { Text("+ Add card") }
            }
            OutlinedTextField(q, { q = it }, label = { Text("Search cards or tags") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            if (cards.isEmpty()) Text("No cards.", Modifier.padding(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(cards, key = { it.id }) { c ->
                    Card(onClick = { edit(c.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text((c.preview ?: "(empty)").take(120), maxLines = 2)
                            if (c.tags.isNotBlank()) Text(c.tags, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
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

data class EItem(val type: String, val data: String)

private val formulaButtons = listOf(
    "x²" to "^{2}", "xₙ" to "_{n}", "√" to "\\sqrt{x}", "a/b" to "\\frac{a}{b}",
    "∫" to "\\int_{a}^{b} f(x)\\,dx", "Σ" to "\\sum_{i=1}^{n} i", "π" to "\\pi", "α" to "\\alpha",
    "β" to "\\beta", "θ" to "\\theta", "Δ" to "\\Delta", "vec" to "\\vec{F}", "lim" to "\\lim_{x\\to 0}",
    "d/dx" to "\\frac{d}{dx}", "×10ⁿ" to "\\times 10^{n}", "matrix" to "\\begin{bmatrix} a & b \\\\ c & d \\end{bmatrix}"
)

@Composable
fun EditorScreen(db: Db, deckId: Long, cardId: Long, dark: Boolean, back: () -> Unit) {
    val dao = db.dao()
    val scope = rememberCoroutineScope()
    val faces = remember { mutableStateListOf(listOf(EItem("TEXT", "")), listOf(EItem("TEXT", ""))) }
    var tags by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf(0) }
    var focusIdx by remember { mutableStateOf(0) }
    var err by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(cardId) {
        if (cardId != 0L) {
            val c = dao.card(cardId)
            val its = dao.items(cardId)
            tags = c?.tags ?: ""
            val g = its.groupBy { it.face }.toSortedMap().values.map { l -> l.map { EItem(it.type, it.data) } }
            faces.clear()
            faces.addAll(g)
            while (faces.size < 2) faces.add(listOf(EItem("TEXT", "")))
        }
    }

    fun save() {
        val clean = faces.map { f -> f.filter { it.data.isNotBlank() } }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) { err = "Add some content first."; return }
        scope.launch {
            val cid: Long
            if (cardId == 0L) {
                cid = dao.insertCard(Flashcard(deckId = deckId, tags = tags.trim()))
            } else {
                dao.card(cardId)?.let { dao.updateCard(it.copy(tags = tags.trim())) }
                dao.deleteItems(cardId)
                cid = cardId
            }
            dao.insertItems(clean.flatMapIndexed { fi, f ->
                f.mapIndexed { pi, e -> Item(cardId = cid, face = fi, pos = pi, type = e.type, data = e.data) }
            })
            back()
        }
    }

    fun setFace(items: List<EItem>) { faces[sel] = items }

    fun insertFormula(t: String) {
        val cur = faces[sel].toMutableList()
        val target = if (focusIdx in cur.indices && cur[focusIdx].type == "LATEX") focusIdx else cur.indexOfLast { it.type == "LATEX" }
        if (target >= 0) cur[target] = cur[target].copy(data = cur[target].data + t) else cur.add(EItem("LATEX", t))
        setFace(cur)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (cardId == 0L) "New card" else "Edit card") },
            navigationIcon = { TextButton(onClick = back) { Text("Back") } },
            actions = {
                if (cardId != 0L) TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                TextButton(onClick = { save() }) { Text("Save") }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp).verticalScroll(rememberScrollState())) {
            ScrollableTabRow(selectedTabIndex = sel.coerceAtMost(faces.lastIndex), edgePadding = 0.dp) {
                faces.forEachIndexed { i, _ -> Tab(selected = sel == i, onClick = { sel = i }, text = { Text("Face ${i + 1}") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (faces.size < 5) TextButton(onClick = { faces.add(listOf(EItem("TEXT", ""))); sel = faces.lastIndex }) { Text("+ Face") }
                if (faces.size > 2) TextButton(onClick = { faces.removeAt(sel); sel = sel.coerceAtMost(faces.lastIndex) }) { Text("Remove face") }
            }
            val cur = faces[sel.coerceAtMost(faces.lastIndex)]
            cur.forEachIndexed { i, e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = e.data,
                        onValueChange = { nv -> setFace(cur.toMutableList().also { l -> l[i] = e.copy(data = nv) }) },
                        label = { Text(if (e.type == "LATEX") "Formula (LaTeX)" else "Text") },
                        textStyle = if (e.type == "LATEX") MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(vertical = 4.dp).onFocusChanged { if (it.isFocused) focusIdx = i }
                    )
                    TextButton(onClick = { setFace(cur.toMutableList().also { l -> l.removeAt(i) }) }) { Text("✕") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { setFace(cur + EItem("TEXT", "")) }) { Text("+ Text") }
                OutlinedButton(onClick = { setFace(cur + EItem("LATEX", "")) }) { Text("+ Formula") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                formulaButtons.forEach { (label, tpl) -> FilledTonalButton(onClick = { insertFormula(tpl) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(label) } }
            }
            Text("Preview", style = MaterialTheme.typography.labelMedium)
            FaceView(cur.filter { it.data.isNotBlank() }.map { it.type to it.data }, dark, Modifier.fillMaxWidth().height(180.dp))
            OutlinedTextField(tags, { tags = it }, label = { Text("Tags (e.g. #physics #mechanics)") }, modifier = Modifier.fillMaxWidth())
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this card?") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                scope.launch { dao.deleteItems(cardId); dao.deleteCard(cardId); back() }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
}

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
            navigationIcon = { TextButton(onClick = back) { Text("Back") } }
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
                    val last = face >= faceList.lastIndex
                    Text("FACE ${face + 1} / ${faceList.size}", style = MaterialTheme.typography.labelLarge)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        FaceView(faceList[face].map { it.type to it.data }, dark, Modifier.fillMaxSize())
                        Box(Modifier.matchParentSize().pointerInput(face, faceList.size) {
                            detectTapGestures { if (!last) face++ }
                        })
                    }
                    if (!last) {
                        Text("Tap to continue", Modifier.padding(12.dp), textAlign = TextAlign.Center)
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
}
