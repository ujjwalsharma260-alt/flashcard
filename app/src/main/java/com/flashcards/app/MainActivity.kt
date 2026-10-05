package com.flashcards.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Db.get(this)
        val prefs = getSharedPreferences("p", 0)
        setContent {
            var theme by remember { mutableStateOf(prefs.getInt("theme", 0)) }
            val dark = when (theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot(db, dark, theme) { theme = it; prefs.edit().putInt("theme", it).apply() }
                }
            }
        }
    }
}
