package com.flashcards.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
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
            val base = AppSettings.base
            val style = AppSettings.style
            val intensity = AppSettings.intensity
            val accent = AppSettings.accent
            val dark = base == 0
            val scheme = buildScheme(base, style, intensity, accent)
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot(db, dark, 0) { /* legacy onTheme unused */ }
                }
            }
        }
    }
}
