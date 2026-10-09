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

/** Every option lives here and can be switched on or off in Settings. */
object AppSettings {
    var theme by mutableStateOf(0)                // 0 System, 1 Light, 2 Charcoal, 3 Slate, 4 Sand, 5 Forest, 6 Plum
    var sessionSize by mutableStateOf(20)         // cards per study set, 0 = all
    var retryMissed by mutableStateOf(true)       // repeat missed cards inside the same set
    var carryOver by mutableStateOf(true)         // bring missed cards back in later sets
    var carryPercent by mutableStateOf(25)
    var streakBonus by mutableStateOf(true)       // right N times in a row -> card appears less often
    var streakN by mutableStateOf(3)
    var streakMult by mutableStateOf(1.5f)
    var flipStyle by mutableStateOf(1)            // 0 smooth fade, 1 3D flip, 2 none
    var ratingStyle by mutableStateOf(0)          // 0 swipe up/down, 1 four buttons
    var showBorder by mutableStateOf(true)        // thin rounded border around the study card
    var cardTheme by mutableStateOf(0)            // 0 Black, 1 Green board, 2 Whiteboard, 3 Paper, 4 Lined
    var hintSeen by mutableStateOf(false)
    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        theme = ctx.getSharedPreferences("p", 0).getInt("theme", 0)
        val p = ctx.getSharedPreferences("settings", 0)
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
        ctx.getSharedPreferences("p", 0).edit().putInt("theme", theme).apply()
        ctx.getSharedPreferences("settings", 0).edit()
            .putInt("sessionSize", sessionSize).putBoolean("retryMissed", retryMissed)
            .putBoolean("carryOver", carryOver).putInt("carryPercent", carryPercent)
            .putBoolean("streakBonus", streakBonus).putInt("streakN", streakN)
            .putFloat("streakMult", streakMult).putInt("flipStyle", flipStyle)
            .putInt("ratingStyle", ratingStyle).putBoolean("showBorder", showBorder)
            .putInt("cardTheme", cardTheme)
            .putBoolean("hintSeen", hintSeen).apply()
    }

    fun reset() {
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

/** All settings, without a screen around them (also used inside the gear dialog while studying). */
@Composable
fun SettingsContent() {
    val ctx = LocalContext.current
    val s = AppSettings
    fun changed(block: () -> Unit) { block(); AppSettings.save(ctx) }

    Column {
        Section("Theme", "Pick how the app looks. All the coloured ones are calm dark themes: Charcoal (grey), Slate (blue-grey), Sand (warm brown), Forest (soft green), Plum (soft purple).") {
            Choices(themeNames.mapIndexed { i, n -> n to i }, s.theme) { v -> changed { s.theme = v } }
        }

        Section("Card background", "Choose how the study card looks. Green board and Black show light text; the others show dark text. Lined adds faint horizontal rules so it feels like notebook paper — the lines are kept light so they never fight your content.") {
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
            "How many cards you study before a short break screen. Example: a chapter has 100 cards and you pick 20. You study 20, then you can start the next 20."
        ) {
            Choices(listOf("10" to 10, "20" to 20, "30" to 30, "50" to 50, "All" to 0), s.sessionSize) { v -> changed { s.sessionSize = v } }
        }

        Section(
            "How you answer",
            "Swipe: swipe the card UP for Easy (double tick), LEFT for Good (single tick), DOWN for Again (cross). Swipe RIGHT to go back one card. Swipe LEFT or RIGHT on the very first faces just turns the card.\n" +
                "Buttons: four buttons appear on the last face: Again, Hard, Good, Easy."
        ) {
            Choices(listOf("Swipe" to 0, "Buttons" to 1), s.ratingStyle) { v -> changed { s.ratingStyle = v } }
        }

        Section(
            "Repeat missed cards in the same set",
            "ON: a card you missed comes back at the end of the same set, again and again, until you get it right. Example: you miss 5 of 20 cards, and those 5 come back until you answer them correctly.\n" +
                "OFF: a missed card is not repeated now. It comes back later by the normal schedule."
        ) { OnOff(s.retryMissed) { v -> changed { s.retryMissed = v } } }

        Section(
            "Bring missed cards back in later sets",
            "ON: cards you got wrong keep showing up in the next sets too, mixed in with new cards, until you answer them right several times in a row. " +
                "Example with 25%: a set of 20 cards = 15 new cards + 5 older cards you struggled with.\nOFF: every set has only new or due cards."
        ) {
            OnOff(s.carryOver) { v -> changed { s.carryOver = v } }
            if (s.carryOver) {
                Text("How much of each set is older missed cards:")
                Choices(listOf("10%" to 10, "25%" to 25, "40%" to 40), s.carryPercent) { v -> changed { s.carryPercent = v } }
            }
        }

        Section(
            "Reward correct streaks",
            "ON: when you answer a card right several times in a row, the app waits longer before showing it again, so you see it less often. " +
                "Example: 3 in a row and x1.5: a card that would come back in 10 days comes back in 15 days. A wrong answer sets the streak back to zero.\nOFF: every card follows the normal schedule only."
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
            "3D flip turns the card like a real card. Smooth fade is lighter. None changes faces instantly. (If your phone has animations switched off, the app respects that.)"
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
