@file:OptIn(ExperimentalMaterial3Api::class)

package com.flashcards.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import kotlinx.coroutines.launch

@Composable
fun ImportScreen(db: Db, startDeck: Long, text: String, back: () -> Unit) {
    val dao = db.dao()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows = remember(text) { Tsv.parse(text) }
    var skipHeader by remember(text) { mutableStateOf(rows.isNotEmpty() && Tsv.looksLikeHeader(rows[0])) }
    val result = remember(rows, skipHeader) { Tsv.analyze(rows, skipHeader) }
    val deckFlow = remember { dao.decks(0L) }
    val decks by deckFlow.collectAsState(emptyList())
    var target by remember { mutableStateOf(startDeck) }
    var newName by remember { mutableStateOf("Imported") }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val hasTabs = text.contains('\t')

    Scaffold(topBar = {
        TopAppBar(title = { Text("Import cards") }, navigationIcon = { BackIcon(back) })
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            if (!hasTabs) {
                Text("No Tab-separated columns were found.", color = MaterialTheme.colorScheme.error)
                Text("Ask the AI for TSV: one card per line, columns separated by Tab characters (not spaces or commas).")
            }
            Text("Detected ${result.cards.size} cards", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = skipHeader, onCheckedChange = { skipHeader = it })
                Text("First row is a header (don't import it)")
            }

            Text("Import into", style = MaterialTheme.typography.labelMedium)
            Box {
                OutlinedButton(onClick = { menu = true }) {
                    Text(if (target == 0L) "New deck" else decks.firstOrNull { it.id == target }?.name ?: "Choose deck")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("New deck") }, onClick = { target = 0L; menu = false })
                    decks.forEach { d -> DropdownMenuItem(text = { Text(d.name) }, onClick = { target = d.id; menu = false }) }
                }
            }
            if (target == 0L) {
                OutlinedTextField(newName, { newName = it }, label = { Text("New deck name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(8.dp))
            if (result.cards.isNotEmpty()) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 1..result.maxCols) Text("Face $c", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                }
                HorizontalDivider()
                result.cards.take(20).forEach { cells ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        for (c in 0 until result.maxCols) {
                            Text(cells.getOrElse(c) { "" }, Modifier.weight(1f).padding(end = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (result.cards.size > 20) Text("… and ${result.cards.size - 20} more", style = MaterialTheme.typography.labelMedium)
            }

            if (result.bad.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("${result.cards.size} valid cards", style = MaterialTheme.typography.titleMedium)
                Text("${result.bad.size} problematic rows (skipped):", color = MaterialTheme.colorScheme.error)
                result.bad.take(20).forEach { (line, why) -> Text("Row $line: $why") }
                if (result.bad.size > 20) Text("… and ${result.bad.size - 20} more")
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = back) { Text("Cancel") }
                Button(enabled = result.cards.isNotEmpty() && !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val deckId = if (target == 0L) dao.insertDeck(Deck(name = newName.trim().ifBlank { "Imported" })) else target
                            var n = 0
                            db.withTransaction {
                                for (cells in result.cards) {
                                    val faces = cells.filter { it.isNotBlank() }
                                    if (faces.isEmpty()) continue
                                    val cid = dao.insertCard(Flashcard(deckId = deckId))
                                    dao.insertItems(faces.mapIndexed { fi, cell ->
                                        val e = Tsv.cellToItem(cell)
                                        Item(cardId = cid, face = fi, pos = 0, type = e.type, data = e.data)
                                    })
                                    n++
                                }
                            }
                            toast(ctx, "Imported $n cards")
                            back()
                        } catch (e: Exception) {
                            busy = false
                            toast(ctx, "Import failed: ${e.message}")
                        }
                    }
                }) { Text(if (result.bad.isEmpty()) "Import ${result.cards.size}" else "Import valid cards (${result.cards.size})") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
