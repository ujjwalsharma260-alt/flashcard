@file:OptIn(ExperimentalMaterial3Api::class)

package com.flashcards.app

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        return parse(s)
    }

    fun parse(s: String): List<KolbEntry> {
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

    fun exportJson(ctx: Context): String = prefs(ctx).getString("list", null) ?: "[]"

    /** Merges reflections from an exported file. Same id: the newer one wins. Returns how many were added or updated. */
    fun importJson(ctx: Context, text: String): Int {
        val incoming = parse(text.trim())
        if (incoming.isEmpty()) return 0
        val merged = load(ctx).associateBy { it.id }.toMutableMap()
        var n = 0
        for (r in incoming) {
            val cur = merged[r.id]
            if (cur == null || r.updated > cur.updated) {
                merged[r.id] = r
                n++
            }
        }
        if (n > 0) write(ctx, merged.values.toList())
        return n
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

// ---------- guide (the template, readable inside the app) ----------

private val gRules = listOf(
    "One cycle = one specific study session or experiment. Never a whole week.",
    "Stay on the same problem for 3 cycles before you switch to a new one.",
    "Do it the same evening, while the details are still fresh.",
    "Fill every box. A short Reflection breaks every step after it.",
    "Finish by writing the question you want answered in the next cycle."
)

private val gGains = listOf(
    "Unconscious incompetence: noticing that a problem exists, and becoming open to being wrong.",
    "Conscious incompetence: spotting your mistakes faster, and finding MORE of the ways you go wrong.",
    "Conscious competence: needing less effort, and being consistent more often.",
    "Unconscious competence: refining it and making it yours."
)

private data class GStep(val n: String, val title: String, val must: String, val good: String, val bad: String)

private val gSteps = listOf(
    GStep(
        "1", "Experience",
        "One specific, recent thing you actually did. A process, never a result.",
        "On 5 Nov I started a study session at 10:40 after closing YouTube, with no written plan. It lasted 25 minutes.",
        "'I did badly on my test' is a result. 'I studied biology' is too broad."
    ),
    GStep(
        "2", "Reflection",
        "Long, in time order, about the process: what you did, felt, tried and avoided. This is the data. If it is one line, everything after it is guesswork.",
        "10:40 filled water, cleared desk, started timer. Opened the chemistry folder and saw three options. Re-read the same page twice. Eight minutes later I picked up my phone.",
        "'I tried to study but had no goal' is a summary, not a sequence. Feelings only ('I felt terrible, terrible') is rumination."
    ),
    GStep(
        "3", "Abstraction",
        "The pattern or habit behind it. You must be able to underline the line in your Reflection that proves it.",
        "The stall began at the point of choosing, not at the point of sitting down. The setup was fine; the decision was not.",
        "'I think it is because I have no timetable' is a guess. 'I am just lazy' is a label, not a cause."
    ),
    GStep(
        "4", "Experiment",
        "One or two actions that test the abstraction, specific enough that you can tell later whether you did them.",
        "Tonight, write tomorrow's three tasks on one note and leave it on the desk. The session starts by reading it. Tally sessions that start within 2 minutes.",
        "'Try harder' is an intention. Eight new things at once is overload. 'Get 80% next time' is a result."
    ),
    GStep(
        "5", "Cycle",
        "The next reflection's Experience IS this experiment. Name it in the first box and pick Yes.",
        "I used the nightly note for 4 sessions: 3 started within 2 minutes, and the 4th failed because I had not written it.",
        "A new problem in every reflection. Nothing compounds, and it feels like Kolb's does not work."
    )
)

private val gChecks = listOf(
    "Experience is a process, not a result, and it is narrow.",
    "My Reflection would let a stranger picture the moment.",
    "Every abstraction can be underlined in my Reflection.",
    "I have 1 to 3 experiments, each traceable to the abstraction.",
    "I can tell tomorrow whether the experiment happened.",
    "The next cycle starts with this experiment."
)

private val gFlags = listOf(
    "Experience: a result, a label, or a whole week.",
    "Reflection: one-liners, feelings only, or a verdict.",
    "Abstraction: 'I think...', nothing to underline, or a character label.",
    "Experiment: 'try harder', 4 or more changes, or unrelated to the abstraction.",
    "Cycling: a new problem every cycle."
)

@Composable
private fun GuideHead(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun GuideBullet(t: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("•  ", style = MaterialTheme.typography.bodyMedium)
        Text(t, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun KolbGuideDialog(onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("How to do a Kolb's", style = MaterialTheme.typography.titleLarge)
                Text("Rules, examples and a check before you close a cycle.", style = MaterialTheme.typography.labelMedium)
                LazyColumn(
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { GuideHead("The 5 rules") }
                    items(gRules) { r -> GuideBullet(r) }
                    item { GuideHead("What counts as a marginal gain") }
                    items(gGains) { r -> GuideBullet(r) }
                    items(gSteps) { s ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(s.n + ". " + s.title, style = MaterialTheme.typography.titleMedium)
                            Text(s.must, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
                            Text("Good: " + s.good, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            Text("Wrong: " + s.bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    item { GuideHead("Check before you close") }
                    items(gChecks) { r -> GuideBullet(r) }
                    item { GuideHead("Red flags") }
                    items(gFlags) { r -> GuideBullet(r) }
                }
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Got it") }
            }
        }
    }
}

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
    var topMenu by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exportL = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = try { writeUriText(ctx, uri, KolbStore.exportJson(ctx)) } catch (e: Exception) { false }
            toast(ctx, if (ok) "KOLBS exported" else "Export failed")
        }
    }
    val importL = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val t = readUriText(ctx, uri)
            val n = if (t.isNullOrBlank()) 0 else KolbStore.importJson(ctx, t)
            items = KolbStore.load(ctx)
            toast(ctx, if (n > 0) "Imported $n reflections" else "Nothing imported (not a KOLBS file, or nothing new)")
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("KOLBS") },
            navigationIcon = { BackIcon(back) },
            actions = {
                TextButton(onClick = { showGuide = true }) { Text("Guide") }
                Box {
                    IconButton(onClick = { topMenu = true }, modifier = Modifier.neumorphIcon()) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                    DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                        DropdownMenuItem(text = { Text("Export all to file") }, onClick = { topMenu = false; exportL.launch("kolbs-backup.json") })
                        DropdownMenuItem(text = { Text("Import from file") }, onClick = { topMenu = false; importL.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text("Copy all as text") }, onClick = {
                            topMenu = false
                            if (items.isEmpty()) {
                                toast(ctx, "Nothing to copy")
                            } else {
                                val txt = items.sortedBy { it.created }.joinToString("\n\n----------\n\n") { r ->
                                    reflectionText(r, items.firstOrNull { it.id == r.followOf })
                                }
                                copyToClipboard(ctx, txt)
                                toast(ctx, "Copied ${items.size} reflections")
                            }
                        })
                    }
                }
            }
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Button(onClick = { newReflection(0L) }, modifier = Modifier.fillMaxWidth()) { Text("+ New reflection") }
            Text(
                "Reflect on an experience in 4 steps: Experience, Reflection, Abstraction, Experiment. " +
                    "Open an old reflection and use Follow up to review how your experiment went. " +
                    "Tap Guide for the rules, examples and self-check.",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            if (items.isEmpty()) Text("No reflections yet. Tap + New reflection.", Modifier.padding(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                items(items, key = { it.id }) { r ->
                    AppCard(modifier = Modifier.fillMaxWidth().clickable { open(r.id) }) {
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
    if (showGuide) KolbGuideDialog(onClose = { showGuide = false })
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
        OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth().neumorphField(labelled = false), minLines = 3, shape = fieldShape(), colors = fieldColors())
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
                    AppCard(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
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
