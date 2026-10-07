@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.flashcards.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

fun stateLabel(state: Int, suspended: Int): String =
    if (suspended == 1) "Suspended" else when (state) {
        0 -> "New"
        1 -> "Learning"
        2 -> "Review"
        else -> "Relearning"
    }

@Composable
fun CardRowItem(r: CardRow, selected: Boolean, showDeck: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLong),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text((if (r.fav == 1) "★ " else "") + (r.preview ?: "(image / voice)").take(120), maxLines = 2)
            val extra = listOfNotNull(stateLabel(r.state, r.suspended), if (showDeck) r.deckName else null, r.tags.ifBlank { null })
            Text(extra.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------- home ----------

@Composable
fun HomeScreen(
    db: Db, theme: Int, onTheme: (Int) -> Unit,
    open: (Long) -> Unit, browse: (String) -> Unit, editCard: (Long, Long) -> Unit, importText: (Long, String) -> Unit
) {
    val dao = db.dao()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val flow = remember { dao.decks(System.currentTimeMillis()) }
    val decks by flow.collectAsState(emptyList())
    var q by remember { mutableStateOf("") }
    val hitFlow = remember(q) {
        if (q.isBlank()) flowOf(emptyList<CardRow>()) else dao.browse(CardSearch.build(q.trim(), 0L, System.currentTimeMillis()))
    }
    val hits by hitFlow.collectAsState(emptyList())
    var dialog by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val shown = decks.filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }

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
            OutlinedTextField(q, { q = it }, label = { Text("Search: words, tag:x, deck:x, has:image, favorite…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val t = clipboardText(ctx)
                    if (t.isNullOrBlank()) toast(ctx, "Clipboard is empty. Copy the TSV from your AI first.") else importText(0L, t)
                }) { Text("Import from Clipboard") }
                OutlinedButton(onClick = { browse("favorite") }) { Text("⭐ Favorites") }
                OutlinedButton(onClick = { browse("added:7d") }) { Text("🆕 Recently added") }
                OutlinedButton(onClick = { browse("") }) { Text("All cards") }
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
                        CardRowItem(h, false, true, { editCard(h.deckId, h.id) }, { })
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

// ---------- deck / card browser (deckId 0 = all decks) ----------

@Composable
fun DeckScreen(
    db: Db, deckId: Long, initialQuery: String, back: () -> Unit,
    edit: (Long, Long) -> Unit, study: () -> Unit, importText: (String) -> Unit
) {
    val dao = db.dao()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val infoFlow = remember { dao.decks(System.currentTimeMillis()) }
    val infos by infoFlow.collectAsState(emptyList())
    val info = infos.firstOrNull { it.id == deckId }
    var q by remember { mutableStateOf(initialQuery) }
    val cardFlow = remember(q) { dao.browse(CardSearch.build(q, deckId, System.currentTimeMillis())) }
    val cards by cardFlow.collectAsState(emptyList())
    val tagFlow = remember { dao.tagLists(deckId) }
    val tagLists by tagFlow.collectAsState(emptyList())
    val allTags = remember(tagLists) {
        tagLists.flatMap { it.split(Regex("\\s+")) }.filter { it.isNotBlank() }.distinct().sorted()
    }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val selecting = selected.isNotEmpty()
    var confirmDeleteDeck by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var tagDialog by remember { mutableStateOf(0) }   // 1 = add, 2 = remove
    var tagText by remember { mutableStateOf("") }
    var moveDialog by remember { mutableStateOf(false) }
    var mergeDialog by remember { mutableStateOf(false) }
    var confirmDeleteCards by remember { mutableStateOf(false) }
    var exportIds by remember { mutableStateOf<List<Long>>(emptyList()) }

    BackHandler(selecting) { selected = emptySet() }

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
    val exportIdsL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/tab-separated-values")) { uri ->
        if (uri != null) scope.launch {
            val ok = try { writeUriText(ctx, uri, Tsv.idsTsv(dao, exportIds)) } catch (e: Exception) { false }
            toast(ctx, if (ok) "Exported ${exportIds.size} cards as TSV" else "Export failed")
        }
    }

    fun runBulk(done: String, block: suspend (List<Long>) -> Unit) {
        val ids = selected.toList()
        scope.launch {
            try { block(ids); toast(ctx, done) } catch (e: Exception) { toast(ctx, "Failed: ${e.message}") }
            selected = emptySet()
        }
    }

    Scaffold(topBar = {
        if (selecting) {
            TopAppBar(
                title = { Text("${selected.size} selected") },
                navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, contentDescription = "Cancel selection") } },
                actions = {
                    TextButton(onClick = { selected = cards.map { it.id }.toSet() }) { Text("All") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Actions") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("⭐ Favorite") }, onClick = { menu = false; runBulk("Favorited") { Bulk.setFav(db, it, 1) } })
                            DropdownMenuItem(text = { Text("Remove favorite") }, onClick = { menu = false; runBulk("Updated") { Bulk.setFav(db, it, 0) } })
                            DropdownMenuItem(text = { Text("Suspend") }, onClick = { menu = false; runBulk("Suspended") { Bulk.setSuspended(db, it, 1) } })
                            DropdownMenuItem(text = { Text("Unsuspend") }, onClick = { menu = false; runBulk("Unsuspended") { Bulk.setSuspended(db, it, 0) } })
                            DropdownMenuItem(text = { Text("Add tag…") }, onClick = { menu = false; tagText = ""; tagDialog = 1 })
                            DropdownMenuItem(text = { Text("Remove tag…") }, onClick = { menu = false; tagText = ""; tagDialog = 2 })
                            DropdownMenuItem(text = { Text("Move to deck…") }, onClick = { menu = false; moveDialog = true })
                            DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; runBulk("Duplicated") { Bulk.duplicateCards(ctx, db, it) } })
                            DropdownMenuItem(text = { Text("Export TSV") }, onClick = { menu = false; exportIds = selected.toList(); exportIdsL.launch("cards.tsv") })
                            DropdownMenuItem(text = { Text("Delete…") }, onClick = { menu = false; confirmDeleteCards = true })
                        }
                    }
                }
            )
        } else {
            TopAppBar(
                title = { if (deckId == 0L) Text("Cards") },
                navigationIcon = { BackIcon(back) },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (q.isNotBlank()) DropdownMenuItem(text = { Text("Export TSV of these results") }, onClick = {
                                menu = false; exportIds = cards.map { it.id }; exportIdsL.launch("cards.tsv")
                            })
                            if (deckId != 0L) {
                                DropdownMenuItem(text = { Text("Rename deck") }, onClick = { menu = false; renameText = info?.name ?: ""; renaming = true })
                                DropdownMenuItem(text = { Text("Duplicate deck") }, onClick = {
                                    menu = false
                                    scope.launch {
                                        val n = try { Bulk.duplicateDeck(ctx, db, deckId, (info?.name ?: "Deck") + " (copy)") } catch (e: Exception) { -1 }
                                        toast(ctx, if (n >= 0) "Deck duplicated ($n cards)" else "Duplicate failed")
                                    }
                                })
                                DropdownMenuItem(text = { Text("Merge into another deck…") }, onClick = { menu = false; mergeDialog = true })
                                DropdownMenuItem(text = { Text("Import TSV from clipboard") }, onClick = {
                                    menu = false
                                    val t = clipboardText(ctx)
                                    if (t.isNullOrBlank()) toast(ctx, "Clipboard is empty") else importText(t)
                                })
                                DropdownMenuItem(text = { Text("Import TSV file") }, onClick = { menu = false; tsvFileL.launch(arrayOf("*/*")) })
                                DropdownMenuItem(text = { Text("Export deck as TSV") }, onClick = { menu = false; exportTsvL.launch("deck.tsv") })
                                DropdownMenuItem(text = { Text("Copy deck TSV to clipboard") }, onClick = {
                                    menu = false
                                    scope.launch { copyToClipboard(ctx, Tsv.deckTsv(dao, deckId)); toast(ctx, "Copied") }
                                })
                                DropdownMenuItem(text = { Text("Copy TSV template") }, onClick = { menu = false; copyToClipboard(ctx, Tsv.TEMPLATE); toast(ctx, "Template copied") })
                            }
                        }
                    }
                    if (deckId != 0L) IconButton(onClick = { confirmDeleteDeck = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete deck") }
                }
            )
        }
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            if (deckId != 0L) {
                Text("${info?.total ?: 0} cards  |  ${info?.fresh ?: 0} new  |  ${info?.due ?: 0} due")
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = study, enabled = (info?.let { it.fresh + it.due } ?: 0) > 0) { Text("Study") }
                    OutlinedButton(onClick = { edit(deckId, 0L) }) { Text("+ Add card") }
                }
            }
            OutlinedTextField(q, { q = it }, label = { Text("Search, or use the filters below") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val quick = listOf("favorite", "suspended", "state:new", "state:learning", "state:review", "due", "overdue", "has:image", "has:audio", "added:today", "added:7d", "added:30d")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (quick + allTags.map { "tag:" + it.trimStart('#') }).forEach { t ->
                    FilterChip(selected = CardSearch.has(q, t), onClick = { q = CardSearch.toggle(q, t) }, label = { Text(t) })
                }
            }
            Text("${cards.size} cards shown" + (if (selecting) "  ·  long-press to select more" else "  ·  long-press a card to select"), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(cards, key = { it.id }) { c ->
                    CardRowItem(
                        c, c.id in selected, deckId == 0L,
                        onClick = {
                            if (selecting) selected = if (c.id in selected) selected - c.id else selected + c.id
                            else edit(c.deckId, c.id)
                        },
                        onLong = { selected = selected + c.id }
                    )
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
    if (confirmDeleteDeck) AlertDialog(
        onDismissRequest = { confirmDeleteDeck = false },
        title = { Text("Delete this deck and its ${info?.total ?: 0} cards? This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDeleteDeck = false
                scope.launch { dao.deleteDeckItems(deckId); dao.deleteDeckCards(deckId); dao.deleteDeck(deckId); back() }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmDeleteDeck = false }) { Text("Cancel") } }
    )
    if (confirmDeleteCards) AlertDialog(
        onDismissRequest = { confirmDeleteCards = false },
        title = { Text("This will delete ${selected.size} cards. Continue?") },
        confirmButton = {
            TextButton(onClick = { confirmDeleteCards = false; runBulk("Deleted") { Bulk.delete(db, it) } }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmDeleteCards = false }) { Text("Cancel") } }
    )
    if (tagDialog != 0) AlertDialog(
        onDismissRequest = { tagDialog = 0 },
        title = { Text(if (tagDialog == 1) "Add tag to ${selected.size} cards" else "Remove tag from ${selected.size} cards") },
        text = { OutlinedTextField(tagText, { tagText = it }, label = { Text("Tag, e.g. class-12") }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                val t = tagText
                val adding = tagDialog == 1
                tagDialog = 0
                if (t.isNotBlank()) runBulk(if (adding) "Tag added" else "Tag removed") { ids ->
                    if (adding) Bulk.addTag(db, ids, t) else Bulk.removeTag(db, ids, t)
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { tagDialog = 0 }) { Text("Cancel") } }
    )
    if (moveDialog) AlertDialog(
        onDismissRequest = { moveDialog = false },
        title = { Text("Move ${selected.size} cards to…") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                infos.forEach { d ->
                    TextButton(onClick = { moveDialog = false; runBulk("Moved to ${d.name}") { Bulk.move(db, it, d.id) } }) { Text(d.name) }
                }
            }
        },
        confirmButton = { },
        dismissButton = { TextButton(onClick = { moveDialog = false }) { Text("Cancel") } }
    )
    if (mergeDialog) AlertDialog(
        onDismissRequest = { mergeDialog = false },
        title = { Text("Move all cards into…  (this deck is then deleted)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                infos.filter { it.id != deckId }.forEach { d ->
                    TextButton(onClick = {
                        mergeDialog = false
                        scope.launch { Bulk.mergeDecks(db, deckId, d.id); toast(ctx, "Merged into ${d.name}"); back() }
                    }) { Text(d.name) }
                }
            }
        },
        confirmButton = { },
        dismissButton = { TextButton(onClick = { mergeDialog = false }) { Text("Cancel") } }
    )
}
