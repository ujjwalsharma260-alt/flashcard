package com.flashcards.app

import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val baseNames = listOf("Dark", "Light")
val styleNames = listOf("Plain", "Colorful", "Liquid")

// Accent colours offered in Settings. 0L = "Auto" (uses a soft purple default).
val accentPalette: List<Pair<String, Long>> = listOf(
    "Auto" to 0L,
    "Blue" to 0xFF448AFFL,
    "Purple" to 0xFFB388FFL,
    "Pink" to 0xFFFF80ABL,
    "Orange" to 0xFFFFAB40L,
    "Green" to 0xFF69F0AEL,
    "Red" to 0xFFFF5252L,
    "Teal" to 0xFF64FFDAL,
    "Yellow" to 0xFFFFD740L
)

// Gradient pool used for the "Colorful" deck tiles.
val tileGradients: List<Pair<Long, Long>> = listOf(
    0xFF7C4DFFL to 0xFF448AFFL,
    0xFFFF6E40L to 0xFFFF5252L,
    0xFFFFD740L to 0xFFFF9100L,
    0xFF69F0AEL to 0xFF00C853L,
    0xFF40C4FFL to 0xFF00B0FFL,
    0xFF448AFFL to 0xFF2962FFL,
    0xFFB388FFL to 0xFF7C4DFFL,
    0xFFFF4081L to 0xFFC51162L,
    0xFF18FFFFL to 0xFF00B8D4L,
    0xFFEA80FCL to 0xFFAA00FFL
)

// Session-stable mapping: which gradient each deck id gets.
private val sessionColors = HashMap<Long, Int>()
private val sessionSeed: Long = System.currentTimeMillis()
fun tileGradientIndexFor(deckId: Long): Int {
    sessionColors[deckId]?.let { return it }
    val mixed = (deckId * 2654435761L) xor sessionSeed
    val idx = ((mixed and 0x7FFFFFFFL) % tileGradients.size.toLong()).toInt()
    sessionColors[deckId] = idx
    return idx
}

// Old API — kept for compatibility (Review.kt calls darkPalette(0) for the study screen).
fun darkPalette(i: Int): ColorScheme = when (i) {
    1 -> palette(0xFF10151C, 0xFF171E27, 0xFF222C38, 0xFFDCE3EC, 0xFF8FA6C2, 0xFF2C3B4E, 0xFF55657A)
    2 -> palette(0xFF1A1511, 0xFF241D17, 0xFF33291F, 0xFFEFE4D2, 0xFFC9A87C, 0xFF4A3A28, 0xFF7A6650)
    3 -> palette(0xFF0F1512, 0xFF161E19, 0xFF222E27, 0xFFDDE8DF, 0xFF9DB89F, 0xFF2F4535, 0xFF5A7360)
    4 -> palette(0xFF16111A, 0xFF1E1724, 0xFF2C2234, 0xFFE6DDEB, 0xFFB7A0C7, 0xFF47345A, 0xFF76608A)
    else -> palette(0xFF141414, 0xFF1C1C1C, 0xFF2A2A2A, 0xFFE6E3DD, 0xFFBDBDBD, 0xFF3A3A3A, 0xFF5A5A5A)
}

// Legacy list, referenced by the old SettingsContent. Kept so nothing breaks if it's still called.
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

// ---- the new theme engine ----
// base: 0 = Dark, 1 = Light
// style: 0 = Plain, 1 = Colorful, 2 = Liquid
// intensity: 0..100 (used for accent strength and glow)
// accent: Long (0L = Auto purple)
fun buildScheme(base: Int, style: Int, intensity: Int, accent: Long): ColorScheme {
    val t = intensity.coerceIn(0, 100) / 100f
    val accentColor = if (accent == 0L) Color(0xFFB388FF) else Color(accent)
    val accentSoft = Color(
        red = accentColor.red * 0.55f + 0.1f,
        green = accentColor.green * 0.55f + 0.1f,
        blue = accentColor.blue * 0.55f + 0.1f
    )

    return if (base == 1) {
        // LIGHT
        val bg = when (style) {
            1 -> Color(0xFFF5F3FF)
            2 -> Color(0xFFF0F4F8)
            else -> Color(0xFFFAFAFA)
        }
        val surface = if (style == 2) Color(0xFFF8FAFC) else Color.White
        val variant = when (style) {
            1 -> Color(0xFFEDE7F6)
            2 -> Color(0xFFE3E9F0)
            else -> Color(0xFFEFEFEF)
        }
        lightColorScheme(
            primary = accentColor, onPrimary = Color.White,
            primaryContainer = variant, onPrimaryContainer = Color(0xFF1A1A1A),
            secondary = accentSoft, onSecondary = Color.White,
            background = bg, onBackground = Color(0xFF1A1A1A),
            surface = surface, onSurface = Color(0xFF1A1A1A),
            surfaceVariant = variant, onSurfaceVariant = Color(0xFF2A2A2A),
            outline = Color(0xFFCCCCCC), outlineVariant = variant
        )
    } else {
        // DARK
        val bg = when (style) {
            1 -> Color(0xFF0A0A12)
            2 -> Color(0xFF0D1015)
            else -> Color(0xFF121212)
        }
        val surface = when (style) {
            1 -> Color(0xFF15151E)
            2 -> Color(0xFF161A20)
            else -> Color(0xFF1C1C1C)
        }
        val variant = when (style) {
            1 -> Color(0xFF23222E)
            2 -> Color(0xFF212630)
            else -> Color(0xFF2A2A2A)
        }
        darkColorScheme(
            primary = accentColor, onPrimary = Color.White,
            primaryContainer = variant, onPrimaryContainer = Color(0xFFECECEC),
            secondary = accentSoft, onSecondary = Color.White,
            background = bg, onBackground = Color(0xFFECECEC),
            surface = surface, onSurface = Color(0xFFECECEC),
            surfaceVariant = variant, onSurfaceVariant = Color(0xFFD6D6D6),
            outline = Color(0xFF3A3A3A), outlineVariant = variant
        )
    }
}

@Composable
fun cardC() = CardDefaults.cardColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant,
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
)
