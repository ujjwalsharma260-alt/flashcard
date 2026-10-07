@file:OptIn(ExperimentalMaterial3Api::class)

package com.flashcards.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Every study option lives here and can be switched on or off in Settings. */
object AppSettings {
    var sessionSize by mutableStateOf(20)         // cards per study set, 0 = all
    var retryMissed by mutableStateOf(true)       // repeat missed (Again) cards inside the same set
    var carryOver by mutableStateOf(true)         // bring missed cards back in later sets
    var carryPercent by mutableStateOf(25)        // share of each later set made of older missed cards
    var streakBonus by mutableStateOf(true)       // correct N times in a row -> card appears less often
    var streakN by mutableStateOf(3)
    var streakMult by mutableStateOf(1.5f)
    var flipStyle by mutableStateOf(0)            // 0 smooth fade, 1 3D flip, 2 none
    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences("settings", 0)
        sessionSize = p.getInt("sessionSize", 20)
        retryMissed = p.getBoolean("retryMissed", true)
        carryOver = p.getBoolean("carryOver", true)
        carryPercent = p.getInt("carryPercent", 25)
        streakBonus = p.getBoolean("streakBonus", true)
        streakN = p.getInt("streakN", 3)
        streakMult = p.getFloat("streakMult", 1.5f)
        flipStyle = p.getInt("flipStyle", 0)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("settings", 0).edit()
            .putInt("sessionSize", sessionSize).putBoolean("retryMissed", retryMissed)
            .putBoolean("carryOver", carryOver).putInt("carryPercent", carryPercent)
            .putBoolean("streakBonus", streakBonus).putInt("streakN", streakN)
            .putFloat("streakMult", streakMult).putInt("flipStyle", flipStyle).apply()
    }

    fun reset() {
        sessionSize = 20; retryMissed = true; carryOver = true; carryPercent = 25
        streakBonus = true; streakN = 3; streakMult = 1.5f; flipStyle = 0
    }
}

@Composable
private fun Section(title: String, desc: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
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
fun SettingsScreen(back: () -> Unit) {
    val ctx = LocalContext.current
    val s = AppSettings
    fun changed(block: () -> Unit) { block(); AppSettings.save(ctx) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackIcon(back) }) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 14.dp).verticalScroll(rememberScrollState())) {
            Section(
                "Cards per study set",
                "How many cards you study before the app stops and gives you a short break screen. " +
                    "Example: a chapter has 100 cards and you pick 20. You study 20, then you can start the next 20."
            ) {
                Choices(listOf("10" to 10, "20" to 20, "30" to 30, "50" to 50, "All" to 0), s.sessionSize) { v -> changed { s.sessionSize = v } }
            }

            Section(
                "Repeat missed cards in the same set",
                "ON: if you press Again on a card, it comes back at the end of the same set, again and again, until you get it right. " +
                    "The next set only starts after that. Example: you miss 5 of 20 cards. Those 5 come back until you answer them correctly.\n" +
                    "OFF: a missed card is not repeated now. It comes back later by the normal schedule."
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = s.retryMissed, onCheckedChange = { v -> changed { s.retryMissed = v } })
                    Spacer(Modifier.width(10.dp)); Text(if (s.retryMissed) "On" else "Off")
                }
            }

            Section(
                "Bring missed cards back in later sets",
                "ON: cards you got wrong keep showing up in the next sets too, mixed in with new cards, until you answer them right several times in a row. " +
                    "Example with 25%: a set of 20 cards = 15 new cards + 5 older cards you struggled with.\n" +
                    "OFF: every set has only new or due cards."
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = s.carryOver, onCheckedChange = { v -> changed { s.carryOver = v } })
                    Spacer(Modifier.width(10.dp)); Text(if (s.carryOver) "On" else "Off")
                }
                if (s.carryOver) {
                    Text("How much of each set is older missed cards:")
                    Choices(listOf("10%" to 10, "25%" to 25, "40%" to 40), s.carryPercent) { v -> changed { s.carryPercent = v } }
                }
            }

            Section(
                "Reward correct streaks",
                "ON: when you answer a card right several times in a row, the app waits longer before showing it again, so you see it less often. " +
                    "Example: with 3 in a row and x1.5, a card that would come back in 10 days comes back in 15 days. " +
                    "Press Again and the streak starts from zero.\n" +
                    "OFF: every card follows the normal schedule only."
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = s.streakBonus, onCheckedChange = { v -> changed { s.streakBonus = v } })
                    Spacer(Modifier.width(10.dp)); Text(if (s.streakBonus) "On" else "Off")
                }
                if (s.streakBonus) {
                    Text("Correct answers in a row needed:")
                    Choices(listOf("2" to 2, "3" to 3, "4" to 4, "5" to 5), s.streakN) { v -> changed { s.streakN = v } }
                    Text("Waiting time is multiplied by:")
                    Choices(listOf("x1.25" to 1.25f, "x1.5" to 1.5f, "x2" to 2f), s.streakMult) { v -> changed { s.streakMult = v } }
                }
            }

            Section(
                "Card turn animation",
                "Smooth fade is the fastest. 3D flip turns the card like a real card (it looks cool, but is heavier). None changes faces instantly."
            ) {
                Choices(listOf("Smooth fade" to 0, "3D flip" to 1, "None" to 2), s.flipStyle) { v -> changed { s.flipStyle = v } }
            }

            OutlinedButton(onClick = { changed { s.reset() } }, modifier = Modifier.padding(vertical = 10.dp)) { Text("Reset all settings") }
            Spacer(Modifier.height(30.dp))
        }
    }
}
