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
    val delim = remember(text) { Tsv.detectDelimiter(text) }
    val rows = remember(text, delim) { Tsv.parse(text, delim) }
    var skipHeader by remember(text) { mutableStateOf(Tsv.looksLikeHeader(rows)) }
    val result = remember(rows, skipHeader) { Tsv.analyze(rows, skipHeader) }
    val deckFlow = remember { dao.decks(0L) }
    val decks by deckFlow.collectAsState(emptyList())
    var target by remember { mutableStateOf(startDeck) }
    var newName by remember { mutableStateOf("Imported") }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var showAll by remember(text) { mutableStateOf(false) }
    val foundSeparator = text.contains('\t') || delim != '\t'

    Scaffold(topBar = {
        TopAppBar(title = { Text("Import cards") }, navigationIcon = { BackIcon(back) })
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            if (!foundSeparator) {
                Text("No column separator was found.", color = MaterialTheme.colorScheme.error)
                Text("Use one card per line, with columns separated by a Tab character or by \" | \" (a pipe with a space on each side).")
            } else if (delim == '|') {
                Text("Columns detected with the | separator.", style = MaterialTheme.typography.labelMedium)
            } else if (delim == ';') {
                Text("Columns detected with the ; separator.", style = MaterialTheme.typography.labelMedium)
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
                OutlinedTextField(newName, { newName = it }, label = { Text("New deck name") }, singleLine = true, shape = fieldShape(), colors = fieldColors(), modifier = Modifier.fillMaxWidth().neumorphField())
            }

            Spacer(Modifier.height(8.dp))
            if (result.ignored > 0) {
                Text(
                    "Ignored ${result.ignored} formatting line" + (if (result.ignored == 1) "" else "s") + " (``` fences, table rules).",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (result.cards.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cards to import (${result.cards.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (result.cards.size > 20) {
                        TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show less" else "Show all ${result.cards.size}") }
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    for (c in 1..result.maxCols) Text("Face $c", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                }
                HorizontalDivider()
                val visible = if (showAll) result.cards else result.cards.take(20)
                visible.forEach { cells ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        for (c in 0 until result.maxCols) {
                            Text(cells.getOrElse(c) { "" }, Modifier.weight(1f).padding(end = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (!showAll && result.cards.size > 20) Text("… and ${result.cards.size - 20} more", style = MaterialTheme.typography.labelMedium)
            }

            if (result.bad.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${result.bad.size} row" + (if (result.bad.size == 1) "" else "s") + " skipped",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        copyToClipboard(ctx, result.bad.joinToString("\n") { it.cells.joinToString("\t") })
                        toast(ctx, "Skipped rows copied")
                    }) { Text("Copy") }
                }
                Text(
                    "These rows were not imported. Each needs 2 to 5 columns separated by a Tab. Fix them and paste again, or import the good cards now.",
                    style = MaterialTheme.typography.bodySmall
                )
                result.bad.take(200).forEach { b ->
                    AppCard(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("Row ${b.line} · ${b.why}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                            Text(
                                b.cells.filter { it.isNotBlank() }.joinToString("  |  ").ifBlank { "(empty)" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (result.bad.size > 200) Text("… and ${result.bad.size - 200} more (Copy gets all of them)", style = MaterialTheme.typography.labelMedium)
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
