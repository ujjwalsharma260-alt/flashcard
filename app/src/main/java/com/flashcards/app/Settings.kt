@file:OptIn(ExperimentalMaterial3Api::class)

package com.flashcards.app

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

object AppSettings {
    // legacy fields kept for compatibility with older code paths
    var theme by mutableStateOf(0)

    // new appearance system
    var base by mutableStateOf(0)        // 0 Dark, 1 Light
    var style by mutableStateOf(0)       // 0 Plain, 1 Colorful, 2 Liquid
    var intensity by mutableStateOf(70)  // 0..100
    var accent by mutableStateOf(0L)     // 0L = auto; else ARGB

    var sessionSize by mutableStateOf(20)
    var retryMissed by mutableStateOf(true)
    var carryOver by mutableStateOf(true)
    var carryPercent by mutableStateOf(25)
    var streakBonus by mutableStateOf(true)
    var streakN by mutableStateOf(3)
    var streakMult by mutableStateOf(1.5f)
    var flipStyle by mutableStateOf(1)
    var ratingStyle by mutableStateOf(0)
    var showBorder by mutableStateOf(true)
    var cardTheme by mutableStateOf(0)
    var hintSeen by mutableStateOf(false)
    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences("settings", 0)
        theme = ctx.getSharedPreferences("p", 0).getInt("theme", 0)
        base = p.getInt("base", 0)
        style = p.getInt("style", 0)
        intensity = p.getInt("intensity", 70)
        accent = p.getLong("accent", 0L)
        sessionSize = p.getInt("sessionSize", 20)
        retryMissed = p.getBoolean("retryMissed", true)
        carryOver = p.getBoolean("carryOver", true)
        carryPercent = p.getInt("carryPercent", 25)
        streakBonus = p.getBoolean("streakBonus", true)
        streakN = p.getInt("streakN", 3)
        streakMult = p.getFloat("streakMult", 1.5f)
        flipStyle = p.getInt("flipStyle", 1)
        ratingStyle = p.getInt("ratingStyle", 0)
        showBorder = p.getBoolean("showBorder", true)
        cardTheme = p.getInt("cardTheme", 0)
        hintSeen = p.getBoolean("hintSeen", false)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("settings", 0).edit()
            .putInt("base", base).putInt("style", style)
            .putInt("intensity", intensity).putLong("accent", accent)
            .putInt("sessionSize", sessionSize).putBoolean("retryMissed", retryMissed)
            .putBoolean("carryOver", carryOver).putInt("carryPercent", carryPercent)
            .putBoolean("streakBonus", streakBonus).putInt("streakN", streakN)
            .putFloat("streakMult", streakMult).putInt("flipStyle", flipStyle)
            .putInt("ratingStyle", ratingStyle).putBoolean("showBorder", showBorder)
            .putInt("cardTheme", cardTheme)
            .putBoolean("hintSeen", hintSeen).apply()
    }

    fun reset() {
        base = 0; style = 0; intensity = 70; accent = 0L
        sessionSize = 20; retryMissed = true; carryOver = true; carryPercent = 25
        streakBonus = true; streakN = 3; streakMult = 1.5f; flipStyle = 1; ratingStyle = 0; showBorder = true
        cardTheme = 0
    }
}

@Composable
private fun Section(title: String, desc: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = cardC()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodyMedium)
            content()
        }
    }
}

@Composable
private fun <T> Choices(options: List<Pair<String, T>>, current: T, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (label, v) -> FilterChip(selected = current == v, onClick = { onPick(v) }, label = { Text(label) }) }
    }
}

@Composable
private fun OnOff(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(10.dp))
        Text(if (checked) "On" else "Off")
    }
}

@Composable
fun SettingsContent() {
    val ctx = LocalContext.current
    val s = AppSettings
    fun changed(block: () -> Unit) { block(); AppSettings.save(ctx) }

    Column {
        Section(
            "Base",
            "Dark puts everything on a deep background. Light uses a bright background. This affects every screen."
        ) {
            Choices(baseNames.mapIndexed { i, n -> n to i }, s.base) { v -> changed { s.base = v } }
        }

        Section(
            "Style",
            "Plain: flat, calm surfaces — the cleanest option.\n" +
                "Colorful: each deck tile gets its own vivid gradient, and the background has soft glowing blobs.\n" +
                "Liquid: frosted glass panels over a soft blurred background — modern and calm."
        ) {
            Choices(styleNames.mapIndexed { i, n -> n to i }, s.style) { v -> changed { s.style = v } }
        }

        Section(
            "Intensity",
            "How strong the colours, glow, and transparency are. 30% is subtle, 70% is balanced, 100% is vivid."
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = s.intensity.toFloat(),
                    onValueChange = { v -> changed { s.intensity = v.toInt() } },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Text("${s.intensity}%")
            }
        }

        Section(
            "Accent colour",
            "The tint used for buttons, the highlight in the title, and (when a deck has no colour of its own) deck tiles. Auto picks a soft purple."
        ) {
            Choices(accentPalette.map { it.first to it.second }, s.accent) { v -> changed { s.accent = v } }
        }

        Section(
            "Card background",
            "How the study card looks while you are answering. Black and Green board show light text; the others show dark text."
        ) {
            Choices(
                listOf(
                    "Black" to 0,
                    "Green board" to 1,
                    "Whiteboard" to 2,
                    "Paper" to 3,
                    "Lined" to 4
                ),
                s.cardTheme
            ) { v -> changed { s.cardTheme = v } }
        }

        Section(
            "Cards per study set",
            "How many cards you study before a short break screen."
        ) {
            Choices(listOf("10" to 10, "20" to 20, "30" to 30, "50" to 50, "All" to 0), s.sessionSize) { v -> changed { s.sessionSize = v } }
        }

        Section(
            "How you answer",
            "Swipe: UP = Easy (double tick), LEFT = Good (single tick), DOWN = Again (cross), RIGHT = go back one card.\n" +
                "Buttons: four buttons appear on the last face: Again, Hard, Good, Easy."
        ) {
            Choices(listOf("Swipe" to 0, "Buttons" to 1), s.ratingStyle) { v -> changed { s.ratingStyle = v } }
        }

        Section(
            "Repeat missed cards in the same set",
            "ON: a missed card comes back at the end of the same set until you get it right. OFF: it only returns later."
        ) { OnOff(s.retryMissed) { v -> changed { s.retryMissed = v } } }

        Section(
            "Bring missed cards back in later sets",
            "ON: cards you got wrong mix into the next sets. OFF: every set has only new or due cards."
        ) {
            OnOff(s.carryOver) { v -> changed { s.carryOver = v } }
            if (s.carryOver) {
                Text("How much of each set is older missed cards:")
                Choices(listOf("10%" to 10, "25%" to 25, "40%" to 40), s.carryPercent) { v -> changed { s.carryPercent = v } }
            }
        }

        Section(
            "Reward correct streaks",
            "ON: answer right several times in a row and the waiting time grows. OFF: every card follows the normal schedule only."
        ) {
            OnOff(s.streakBonus) { v -> changed { s.streakBonus = v } }
            if (s.streakBonus) {
                Text("Correct answers in a row needed:")
                Choices(listOf("2" to 2, "3" to 3, "4" to 4, "5" to 5), s.streakN) { v -> changed { s.streakN = v } }
                Text("Waiting time is multiplied by:")
                Choices(listOf("x1.25" to 1.25f, "x1.5" to 1.5f, "x2" to 2f), s.streakMult) { v -> changed { s.streakMult = v } }
            }
        }

        Section(
            "Card turn animation",
            "3D flip turns the card like a real card. Smooth fade is lighter. None changes faces instantly."
        ) {
            Choices(listOf("3D flip" to 1, "Smooth fade" to 0, "None" to 2), s.flipStyle) { v -> changed { s.flipStyle = v } }
        }

        Section("Card border", "Shows a thin rounded outline around the study card.") {
            OnOff(s.showBorder) { v -> changed { s.showBorder = v } }
        }

        Section("Other", "Reset helpers.") {
            OutlinedButton(onClick = { changed { s.hintSeen = false } }) { Text("Show the swipe hint again") }
            OutlinedButton(onClick = { Resume.forgetChoices(ctx) }) { Text("Forget remembered resume choices") }
            OutlinedButton(onClick = { changed { s.reset() } }) { Text("Reset all settings") }
        }
    }
}

@Composable
fun SettingsScreen(back: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackIcon(back) }) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 14.dp).verticalScroll(rememberScrollState())) {
            SettingsContent()
            Spacer(Modifier.height(30.dp))
        }
    }
}
