package com.flashcards.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    /** Asks Android to run this app at the screen's highest refresh rate (e.g. 120 Hz) instead of 60 Hz. */
    @Suppress("DEPRECATION")
    private fun requestHighRefreshRate() {
        try {
            val d = windowManager.defaultDisplay
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate }
            if (best != null) {
                val lp = window.attributes
                lp.preferredDisplayModeId = best.modeId
                window.attributes = lp
            }
        } catch (e: Exception) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        requestHighRefreshRate()
        val db = Db.get(this)
        setContent {
            val theme = AppSettings.theme      // 0 System, 1 Light, 2..6 = Charcoal, Slate, Sand, Forest, Plum
            val sysDark = isSystemInDarkTheme()
            val dark = theme >= 2 || (theme == 0 && sysDark)
            val scheme = if (!dark) lightColorScheme() else darkPalette(if (theme >= 2) theme - 2 else 0)
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot(db, dark, theme) { AppSettings.theme = it; AppSettings.save(this@MainActivity) }
                }
            }
        }
    }
}
