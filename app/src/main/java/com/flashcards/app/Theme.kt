package com.flashcards.app

import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val themeNames = listOf("System", "Light", "Charcoal", "Slate", "Sand", "Forest", "Plum")

private fun palette(bg: Long, surface: Long, variant: Long, on: Long, primary: Long, container: Long, outline: Long): ColorScheme =
    darkColorScheme(
        primary = Color(primary), onPrimary = Color(0xFF111111),
        primaryContainer = Color(container), onPrimaryContainer = Color(on),
        secondary = Color(primary), onSecondary = Color(0xFF111111),
        secondaryContainer = Color(container), onSecondaryContainer = Color(on),
        tertiary = Color(primary), onTertiary = Color(0xFF111111),
        background = Color(bg), onBackground = Color(on),
        surface = Color(surface), onSurface = Color(on),
        surfaceVariant = Color(variant), onSurfaceVariant = Color(on),
        outline = Color(outline), outlineVariant = Color(variant)
    )

/** Five muted dark themes: 0 Charcoal, 1 Slate, 2 Sand, 3 Forest, 4 Plum. */
fun darkPalette(i: Int): ColorScheme = when (i) {
    1 -> palette(0xFF10151C, 0xFF171E27, 0xFF222C38, 0xFFDCE3EC, 0xFF8FA6C2, 0xFF2C3B4E, 0xFF55657A)
    2 -> palette(0xFF1A1511, 0xFF241D17, 0xFF33291F, 0xFFEFE4D2, 0xFFC9A87C, 0xFF4A3A28, 0xFF7A6650)
    3 -> palette(0xFF0F1512, 0xFF161E19, 0xFF222E27, 0xFFDDE8DF, 0xFF9DB89F, 0xFF2F4535, 0xFF5A7360)
    4 -> palette(0xFF16111A, 0xFF1E1724, 0xFF2C2234, 0xFFE6DDEB, 0xFFB7A0C7, 0xFF47345A, 0xFF76608A)
    else -> palette(0xFF141414, 0xFF1C1C1C, 0xFF2A2A2A, 0xFFE6E3DD, 0xFFBDBDBD, 0xFF3A3A3A, 0xFF5A5A5A)
}

@Composable
fun cardC() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
)
