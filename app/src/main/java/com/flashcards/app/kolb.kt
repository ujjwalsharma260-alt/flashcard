@file:OptIn(ExperimentalMaterial3Api::class)

package com.flashcards.app

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- data + storage (SharedPreferences "kolb", one JSON list; does not touch the card database) ----------

data class KolbEntry(
    val id: Long,
    val created: Long,
    val updated: Long,
    val followOf: Long,            // id of the earlier reflection whose experiment this one reviews, 0 = none
    val a: Map<String, String>     // answers by question key
)

object KolbStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("kolb", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<KolbEntry> {
        val s = prefs(ctx).getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            val out = ArrayList<KolbEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val answers = HashMap<String, String>()
                val ao = o.optJSONObject("a")
                if (ao != null) {
                    val keys = ao.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        answers[k] = ao.optString(k, "")
                    }
                }
                out.add(KolbEntry(o.optLong("id"), o.optLong("created"), o.optLong("updated"), o.optLong("followOf", 0L), answers))
            }
            out.sortedByDescending { it.updated }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(ctx: Context, list: List<KolbEntry>) {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("id", r.id)
            o.put("created", r.created)
            o.put("updated", r.updated)
            o.put("followOf", r.followOf)
            val ao = JSONObject()
            for ((k, v) in r.a) ao.put(k, v)
            o.put("a", ao)
            arr.put(o)
        }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
    }

    fun upsert(ctx: Context, r: KolbEntry) {
        val list = load(ctx).filter { it.id != r.id } + r
        write(ctx, list)
    }

    fun delete(ctx: Context, id: Long) {
        val list = load(ctx)
        if (list.any { it.id == id }) write(ctx, list.filter { it.id != id })
    }
}

// ---------- the questions (from the Kolb's exemplars) ----------

private data class KQ(val key: String, val label: String, val hint: String)
private data class KStep(val title: String, val subtitle: String, val qs: List<KQ>)

private val kolbSteps = listOf(
    KStep("Experience", "Set the context", listOf(
        KQ("what", "What experience do you want to reflect on?", "One specific event, study session or task."),
        KQ("gain", "What would a marginal gain look like?", "Where are you now, and what small improvement are you aiming for?")
    )),
    KStep("Reflection", "Describe what happened", listOf(
        KQ("seq", "List and describe the sequence of events, in chronological order", "Step by step, as it actually happened."),
        KQ("feel", "How did you feel about the experience?", "For example frustrated, anxious, happy, motivated."),
        KQ("hard", "Which aspects felt especially difficult?", "Obstacles, temptations, points of resistance."),
        KQ("well", "Which aspects felt like they went well?", "Anything that worked, even a little."),
        KQ("resp", "How did you respond to challenges and difficulties during this process?", "What did you actually do when it got hard?"),
        KQ("trig", "What were the triggers to you feeling the way you did?", "What set off the feeling or the behaviour?"),
        KQ("why", "Why do you think you acted the way you did during this experience?", "Your best honest explanation.")
    )),
    KStep("Abstraction", "Find the root causes and patterns", listOf(
        KQ("habits", "What habits, beliefs, and tendencies can you identify from your reflection that explains why you acted the way you did?", "Look for tendencies, not one-off events."),
        KQ("similar", "Do you act or respond in similar ways in other parts of your life?", "Work, home, friends, hobbies.")
    )),
    KStep("Experiment", "Plan what to try next time", listOf(
        KQ("next", "What do you think you could do next time?", "One small, specific action, ideally with a trigger (for example a calendar reminder).")
    ))
)

private fun fmtDate(ms: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

private fun reflectionText(r: KolbEntry, prev: KolbEntry?): String {
    val sb = StringBuilder()
    sb.append("KOLB'S REFLECTION - ").append(fmtDate(r.created)).append("\n")
    if (prev != null) {
        sb.append("Follow-up on the experiment: ").append(prev.a["next"].orEmpty().trim()).append("\n")
    }
    for (s in kolbSteps) {
        sb.append("\n").append(s.title.uppercase()).append("\n")
        for (q in s.qs) {
            val v = r.a[q.key].orEmpty().trim()
            if (v.isNotEmpty()) sb.append(q.label).append("\n").append(v).append("\n\n")
        }
    }
    return sb.toString().trim()
}

// ---------- list screen ----------

@Composable
fun KolbListScreen(back: () -> Unit, open: (Long) -> Unit, newReflection: (Long) -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(KolbStore.load(ctx)) }
    var menuId by remember { mutableStateOf(0L) }
    var deleteTarget by remember { mutableStateOf<KolbEntry?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("KOLBS") }, navigationIcon = { BackIcon(back) })
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Button(onClick = { newReflection(0L) }, modifier = Modifier.fillMaxWidth()) { Text("+ New reflection") }
            Text(
                "Reflect on an experience in 4 steps: Experience, Reflection, Abstraction, Experiment. " +
                    "Open an old reflection and use Follow up to review how your experiment went.",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            if (items.isEmpty()) Text("No reflections yet. Tap + New reflection.", Modifier.padding(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                items(items, key = { it.id }) { r ->
                    Card(colors = cardC(), modifier = Modifier.fillMaxWidth().clickable { open(r.id) }) {
                        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    fmtDate(r.updated) + "  ·  " + (if (r.followOf != 0L) "Cycle 2 (follow-up)" else "Cycle 1"),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(r.a["what"].orEmpty().ifBlank { "(no title yet)" }.take(120), maxLines = 2)
                            }
                            Box {
                                IconButton(onClick = { menuId = r.id }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                                DropdownMenu(expanded = menuId == r.id, onDismissRequest = { menuId = 0L }) {
                                    DropdownMenuItem(text = { Text("Open / edit") }, onClick = { menuId = 0L; open(r.id) })
                                    DropdownMenuItem(text = { Text("Follow up on this experiment") }, onClick = { menuId = 0L; newReflection(r.id) })
                                    DropdownMenuItem(text = { Text("Copy as text") }, onClick = {
                                        menuId = 0L
                                        val prev = items.firstOrNull { it.id == r.followOf }
                                        copyToClipboard(ctx, reflectionText(r, prev))
                                        toast(ctx, "Copied")
                                    })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuId = 0L; deleteTarget = r })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this reflection? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    KolbStore.delete(ctx, t.id)
                    items = KolbStore.load(ctx)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

// ---------- editor (4 steps, autosaves while you type) ----------

@Composable
private fun KField(label: String, hint: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(hint, style = MaterialTheme.typography.labelSmall)
        OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), minLines = 3)
    }
}

@Composable
fun KolbEditScreen(id: Long, followOf: Long, back: () -> Unit) {
    val ctx = LocalContext.current
    val all = remember { KolbStore.load(ctx) }
    val existing = remember { all.firstOrNull { it.id == id } }
    val rid = remember { if (existing != null) existing.id else System.currentTimeMillis() }
    val created = remember { existing?.created ?: System.currentTimeMillis() }
    var link by remember { mutableStateOf(existing?.followOf ?: followOf) }
    val answers = remember {
        val m = mutableStateMapOf<String, String>()
        existing?.let { e -> for ((k, v) in e.a) m[k] = v }
        m
    }
    var step by rememberSaveable { mutableStateOf(0) }
    var removed by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val candidates = remember { all.filter { it.id != rid && it.a["next"].orEmpty().isNotBlank() } }
    val prev = all.firstOrNull { link != 0L && it.id == link }

    fun persist() {
        if (removed) return
        val a = answers.toMap()
        if (a.values.all { it.isBlank() }) {
            KolbStore.delete(ctx, rid)
            return
        }
        KolbStore.upsert(ctx, KolbEntry(rid, created, System.currentTimeMillis(), link, a))
    }

    val snap = answers.toMap()
    LaunchedEffect(snap, link) {
        delay(400)
        persist()
    }
    DisposableEffect(Unit) {
        onDispose { persist() }
    }
    LaunchedEffect(step) { scroll.scrollTo(0) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Kolb's reflection") },
            navigationIcon = { BackIcon(back) },
            actions = {
                TextButton(onClick = {
                    val e = KolbEntry(rid, created, System.currentTimeMillis(), link, answers.toMap())
                    copyToClipboard(ctx, reflectionText(e, prev))
                    toast(ctx, "Copied")
                }) { Text("Copy") }
                if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            ScrollableTabRow(selectedTabIndex = step, edgePadding = 0.dp) {
                kolbSteps.forEachIndexed { i, s ->
                    Tab(selected = step == i, onClick = { step = i }, text = { Text("${i + 1}. ${s.title}") })
                }
            }
            Column(Modifier.weight(1f).verticalScroll(scroll)) {
                val s = kolbSteps[step]
                Text(
                    "Step ${step + 1} of ${kolbSteps.size}: ${s.subtitle}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
                if (prev != null && step <= 1) {
                    Card(colors = cardC(), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Experiment you planned last time", style = MaterialTheme.typography.labelLarge)
                            Text(prev.a["next"].orEmpty().ifBlank { "(nothing written)" })
                        }
                    }
                }
                if (step == 0) {
                    Text(
                        "Is this reflecting on an experiment from a previous Kolb's?",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = link == 0L, onClick = { link = 0L }, label = { Text("No") })
                        FilterChip(selected = link != 0L, onClick = {
                            if (candidates.isEmpty()) toast(ctx, "No earlier reflection with a plan was found.") else pick = true
                        }, label = { Text("Yes") })
                    }
                }
                s.qs.forEach { q ->
                    KField(q.label, q.hint, answers[q.key].orEmpty()) { nv -> answers[q.key] = nv }
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = step > 0, onClick = { step = step - 1 }, modifier = Modifier.weight(1f)) { Text("Back") }
                if (step < kolbSteps.lastIndex) {
                    Button(onClick = { step = step + 1 }, modifier = Modifier.weight(1f)) { Text("Next") }
                } else {
                    Button(onClick = { persist(); toast(ctx, "Saved"); back() }, modifier = Modifier.weight(1f)) { Text("Done") }
                }
            }
        }
    }

    if (pick) AlertDialog(
        onDismissRequest = { pick = false },
        title = { Text("Which earlier experiment?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                candidates.forEach { c ->
                    TextButton(onClick = { link = c.id; pick = false }) {
                        Text(fmtDate(c.created) + ": " + c.a["next"].orEmpty().take(50))
                    }
                }
            }
        },
        confirmButton = { },
        dismissButton = { TextButton(onClick = { pick = false }) { Text("Cancel") } }
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this reflection? This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = {
                confirmDelete = false
                removed = true
                KolbStore.delete(ctx, rid)
                back()
            }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
}
