package com.flashcards.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val baseNames = listOf("Dark", "Light")
val styleNames = listOf("Plain", "Colorful", "Liquid", "Clay", "Neumorph")

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
    val neumo = style == 4
    // Neumorph lives on a pale grey sheet, so a pastel accent would disappear on it.
    // Its own default is the saturated blue from the reference sheets.
    val rawAccent = when {
        accent != 0L -> Color(accent)
        neumo -> Color(0xFF3A6BF0)
        else -> Color(0xFFB388FF)
    }
    // Pale accents (yellow, mint, pink) are darkened on a light base so white button text
    // still reads. Dark bases keep exactly the accent the user picked.
    val accentColor = if (base == 1 && neumo) readableAccent(rawAccent) else rawAccent
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
            3 -> Color(0xFFF1EAE3)
            4 -> Color(0xFFE4E7EE)
            else -> Color(0xFFFAFAFA)
        }
        val surface = when (style) {
            2 -> Color(0xFFF8FAFC)
            3 -> Color(0xFFFBF5EF)
            // Neumorph: the bar and the page are one sheet, so nothing has a visible band.
            4 -> Color(0xFFE4E7EE)
            else -> Color.White
        }
        val variant = when (style) {
            1 -> Color(0xFFEDE7F6)
            2 -> Color(0xFFE3E9F0)
            3 -> Color(0xFFFFE3D3)
            // Neumorph: cards sit a tiny step lighter than the page - that is the "raised".
            4 -> Color(0xFFEAEDF4)
            else -> Color(0xFFEFEFEF)
        }
        // Clay uses warm brown ink instead of near-black, so the pastel fills do not glare.
        val ink = when (style) {
            3 -> Color(0xFF3A2E27)
            4 -> Color(0xFF3B414D)
            else -> Color(0xFF1A1A1A)
        }
        val inkSoft = when (style) {
            3 -> Color(0xFF5C4033)
            4 -> Color(0xFF6B7280)
            else -> Color(0xFF2A2A2A)
        }
        val line = when (style) {
            3 -> Color(0xFFD9C7B8)
            4 -> Color(0xFFD5DAE3)
            else -> Color(0xFFCCCCCC)
        }
        // FABs and selected rows read as pale raised pills with accent text.
        val raised = if (neumo) Color(0xFFF2F5FA) else variant
        val onRaised = if (neumo) accentColor else ink
        lightColorScheme(
            primary = accentColor, onPrimary = Color.White,
            primaryContainer = raised, onPrimaryContainer = onRaised,
            secondary = accentSoft, onSecondary = Color.White,
            background = bg, onBackground = ink,
            surface = surface, onSurface = ink,
            surfaceVariant = variant, onSurfaceVariant = inkSoft,
            outline = line, outlineVariant = variant,
            tertiary = accentColor, onTertiary = Color.White,
            tertiaryContainer = variant, onTertiaryContainer = ink,
            surfaceTint = if (neumo) surface else accentColor,
            inverseSurface = Color(0xFF303030), inverseOnSurface = Color(0xFFF5F5F5),
            inversePrimary = accentColor,
            error = Color(0xFFB3261E), onError = Color.White,
            errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
            scrim = Color(0xFF000000)
        )
    } else {
        // DARK
        val bg = when (style) {
            1 -> Color(0xFF0A0A12)
            2 -> Color(0xFF0D1015)
            3 -> Color(0xFF2A2320)
            4 -> Color(0xFF22252C)
            else -> Color(0xFF121212)
        }
        val surface = when (style) {
            1 -> Color(0xFF15151E)
            2 -> Color(0xFF161A20)
            3 -> Color(0xFF332B27)
            4 -> Color(0xFF22252C)
            else -> Color(0xFF1C1C1C)
        }
        val variant = when (style) {
            1 -> Color(0xFF23222E)
            2 -> Color(0xFF212630)
            3 -> Color(0xFF3F342E)
            4 -> Color(0xFF2A2E37)
            else -> Color(0xFF2A2A2A)
        }
        val ink = when (style) {
            3 -> Color(0xFFF3EAE3)
            4 -> Color(0xFFE7E9EE)
            else -> Color(0xFFECECEC)
        }
        val inkSoft = when (style) {
            3 -> Color(0xFFE0D2C8)
            4 -> Color(0xFFB4B9C4)
            else -> Color(0xFFD6D6D6)
        }
        val line = when (style) {
            3 -> Color(0xFF5A4B41)
            4 -> Color(0xFF3A3F4A)
            else -> Color(0xFF3A3A3A)
        }
        val raised = if (neumo) Color(0xFF2E323B) else variant
        val onRaised = if (neumo) accentColor else ink
        darkColorScheme(
            primary = accentColor, onPrimary = Color.White,
            primaryContainer = raised, onPrimaryContainer = onRaised,
            secondary = accentSoft, onSecondary = Color.White,
            background = bg, onBackground = ink,
            surface = surface, onSurface = ink,
            surfaceVariant = variant, onSurfaceVariant = inkSoft,
            outline = line, outlineVariant = variant,
            tertiary = accentColor, onTertiary = Color(0xFF111111),
            tertiaryContainer = variant, onTertiaryContainer = ink,
            surfaceTint = if (neumo) surface else accentColor,
            inverseSurface = Color(0xFFECECEC), inverseOnSurface = Color(0xFF1A1A1A),
            inversePrimary = accentColor,
            error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
            scrim = Color(0xFF000000)
        )
    }
}

// Keeps a picked accent readable on a pale sheet: only genuinely light colours are
// darkened, so blue/red/orange are left exactly as chosen.
private fun readableAccent(c: Color): Color {
    val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    return if (lum > 0.55f) Color(c.red * 0.72f, c.green * 0.72f, c.blue * 0.72f) else c
}


// ---- design tokens ----
// A theme is data, not if/else in the screens. Step 1 keeps the exact same values the
// app already used (Material 3 defaults: 12dp corners, 1dp shadow), so nothing looks
// different yet. Later styles only change these numbers.

data class SurfaceSpec(
    val fill: Color,
    val content: Color,
    val border: Color? = null,
    val corner: Dp = 12.dp,
    val shadow: Dp = 1.dp,
    val shadowColor: Color? = null,   // tinted shadow; Android 9+ only, older phones use grey
    val blur: Boolean = false,        // glass only, and only on Android 12+
    val innerLight: Boolean = false,  // clay only (the puffy highlight)
    // Neumorph only: a dark shade bottom-right plus a light glow top-left. When both are
    // set, AppCard draws them itself instead of using Modifier.shadow.
    val darkShadow: Color? = null,
    val lightShadow: Color? = null,
    val raise: Dp = 0.dp,             // how far the two soft shadows reach out
    val sheen: Float = 0f             // strength of the white highlight on top (0 = none)
)

data class AppThemeSpec(
    val id: String,
    val name: String,
    val neumorph: Boolean = false,
    val scheme: ColorScheme,
    val shapes: Shapes,
    val card: SurfaceSpec,
    val dialog: SurfaceSpec,
    val favorite: Color,
    val highlight: Color,
    val onHighlight: Color,
    val onMedia: Color
)

// One number changes the roundness of every Material component: buttons, chips, tabs,
// text fields, dialogs, menus. 1f = exactly what the app uses today.
fun themeShapes(scale: Float): Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp * scale),
    small = RoundedCornerShape(8.dp * scale),
    medium = RoundedCornerShape(12.dp * scale),
    large = RoundedCornerShape(16.dp * scale),
    extraLarge = RoundedCornerShape(28.dp * scale)
)

fun themeSpec(base: Int, style: Int, intensity: Int, accent: Long, cornerScale: Float = 1f): AppThemeSpec {
    val scheme = buildScheme(base, style, intensity, accent)
    val baseName = if (base == 1) "Light" else "Dark"
    val styleName = styleNames.getOrNull(style) ?: "Plain"
    val clay = style == 3
    val neumo = style == 4
    val light = base == 1
    // Clay: rounder everything, a soft warm shadow and a light edge. All of this is
    // ordinary drawing — no Android 12 features — so it works on every supported phone.
    val edge = if (light) Color(0x99FFFFFF) else Color(0x1FFFFFFF)
    return AppThemeSpec(
        id = "b" + base + "_s" + style,
        name = baseName + " " + styleName,
        neumorph = neumo,
        scheme = scheme,
        shapes = themeShapes(
            when {
                neumo -> cornerScale * 1.15f
                clay -> cornerScale * 1.25f
                else -> cornerScale
            }
        ),
        card = SurfaceSpec(
            fill = scheme.surfaceVariant,
            content = scheme.onSurfaceVariant,
            border = if (clay) edge else null,
            corner = when {
                neumo -> 22.dp * cornerScale
                clay -> 28.dp * cornerScale
                else -> 12.dp * cornerScale
            },
            shadow = when {
                neumo -> 0.dp
                clay -> 10.dp
                else -> 1.dp
            },
            shadowColor = if (clay) Color(0xFF9C7A5E) else null,
            innerLight = clay,
            // Neumorph keeps the platform shadow at zero and draws its own pair instead.
            darkShadow = if (neumo) {
                if (light) Color(0x66A6AFC4) else Color(0xB3000000)
            } else null,
            lightShadow = if (neumo) {
                if (light) Color(0xE6FFFFFF) else Color(0x1FFFFFFF)
            } else null,
            raise = if (neumo) 12.dp else 0.dp,
            sheen = if (neumo) (if (light) 0.30f else 0.10f) else 0f
        ),
        dialog = SurfaceSpec(
            fill = scheme.surface,
            content = scheme.onSurface,
            corner = (if (clay) 32.dp else 28.dp) * cornerScale,
            shadow = if (clay) 16.dp else 1.dp
        ),
        favorite = Color(0xFFFFB300),
        highlight = Color(0xFFFFD54F),
        onHighlight = Color.Black,
        onMedia = Color.White
    )
}


// Set once in MainActivity; any screen can read LocalTheme.current.card etc.
val LocalTheme = staticCompositionLocalOf { themeSpec(0, 0, 70, 0L) }

// The colour of the surface an element is sitting on. Neumorphism only works when a raised
// chip is the same colour as the card behind it, so AppCard publishes its own fill here.
val LocalSurfaceFill = staticCompositionLocalOf { Color(0xFFE4E7EE) }

// ---- neumorphic painters ----
// Raised: a dark band bottom-right and a light band top-left. Each band is a stack of thin
// rings whose alpha fades outward, so it looks blurred without a blur filter (API 26 safe).
fun Modifier.raisedSoft(spec: SurfaceSpec, reach: Dp = spec.raise): Modifier {
    val dark = spec.darkShadow
    val glare = spec.lightShadow
    if (dark == null || glare == null || reach <= 0.dp) return this
    return this.drawBehind {
        val cr = CornerRadius(spec.corner.toPx())
        val r = reach.toPx()
        val d = r * 0.35f
        val steps = 8
        val step = r / steps
        for (i in steps downTo 1) {
            val grow = step * i
            val fade = 1f - (i - 1) / steps.toFloat()
            drawRoundRect(
                color = dark.copy(alpha = dark.alpha * fade * 0.55f),
                topLeft = Offset(d - grow, d - grow),
                size = Size(size.width + grow * 2f, size.height + grow * 2f),
                cornerRadius = CornerRadius(cr + grow),
                style = Stroke(width = step * 1.6f)
            )
        }
        for (i in steps downTo 1) {
            val grow = step * i
            val fade = 1f - (i - 1) / steps.toFloat()
            drawRoundRect(
                color = glare.copy(alpha = glare.alpha * fade * 0.70f),
                topLeft = Offset(-d - grow, -d - grow),
                size = Size(size.width + grow * 2f, size.height + grow * 2f),
                cornerRadius = CornerRadius(cr + grow),
                style = Stroke(width = step * 1.6f)
            )
        }
    }
}

// Pressed: the same idea turned inward. Four soft gradients along the top/left (dark) and
// bottom/right (light) edges, clipped to the shape. Used by selected chips and text fields.
fun Modifier.insetSoft(spec: SurfaceSpec, shape: Shape, depth: Dp = spec.raise): Modifier {
    val dark = spec.darkShadow
    val glare = spec.lightShadow
    if (dark == null || glare == null || depth <= 0.dp) return this
    return this.clip(shape).drawBehind {
        val w = depth.toPx() * 1.6f
        val dk = dark.copy(alpha = dark.alpha * 0.85f)
        val lg = glare.copy(alpha = glare.alpha * 0.90f)
        drawRect(
            brush = Brush.verticalGradient(0f to dk, 1f to Color.Transparent, startY = 0f, endY = w),
            size = Size(size.width, w)
        )
        drawRect(
            brush = Brush.horizontalGradient(0f to dk, 1f to Color.Transparent, startX = 0f, endX = w),
            size = Size(w, size.height)
        )
        drawRect(
            brush = Brush.verticalGradient(0f to Color.Transparent, 1f to lg, startY = size.height - w, endY = size.height),
            topLeft = Offset(0f, size.height - w),
            size = Size(size.width, w)
        )
        drawRect(
            brush = Brush.horizontalGradient(0f to Color.Transparent, 1f to lg, startX = size.width - w, endX = size.width),
            topLeft = Offset(size.width - w, 0f),
            size = Size(w, size.height)
        )
    }
}

@Composable
fun cardC() = CardDefaults.cardColors(
    containerColor = LocalTheme.current.card.fill,
    contentColor = LocalTheme.current.card.content
)

// The single painter every screen uses for cards. Everything a theme needs to say about
// a card lives in SurfaceSpec: fill, edge, corner radius, shadow and the clay highlight.
// `fill` / `contentColor` are only for state colouring (e.g. a selected row).
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    fill: Color? = null,
    contentColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val s = LocalTheme.current.card
    val shape = RoundedCornerShape(s.corner)
    val neumo = s.lightShadow != null && s.darkShadow != null && s.raise > 0.dp
    val shadowMod = when {
        neumo -> Modifier.raisedSoft(s)
        s.shadowColor != null -> Modifier.shadow(
            elevation = s.shadow,
            shape = shape,
            clip = false,
            ambientColor = s.shadowColor,
            spotColor = s.shadowColor
        )
        else -> Modifier.shadow(elevation = s.shadow, shape = shape, clip = false)
    }
    Surface(
        modifier = modifier.then(shadowMod),
        shape = shape,
        color = fill ?: s.fill,
        contentColor = contentColor ?: s.content,
        shadowElevation = 0.dp,
        border = s.border?.let { BorderStroke(1.dp, it) }
    ) {
        Box {
            if (s.innerLight || s.sheen > 0f) {
                // Puffy top edge: clay's white glow, or neumorph's softer sheen.
                val a = if (s.sheen > 0f) s.sheen else 0.40f
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.White.copy(alpha = a),
                                0.42f to Color.White.copy(alpha = a * 0.12f),
                                1f to Color.Transparent
                            )
                        )
                )
            }
            CompositionLocalProvider(LocalSurfaceFill provides (fill ?: s.fill)) {
                Column { content() }
            }
        }
    }
}

// ---------- neumorphic widgets ----------
// Screens call these instead of the Material component. Every style except Neumorph falls
// straight through to plain Material 3, so the other four themes cannot regress.

@Composable
fun AppIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val t = LocalTheme.current
    if (!t.neumorph) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
        return
    }
    val s = t.card
    Box(
        modifier = modifier
            .size(44.dp)
            .raisedSoft(s, s.raise * 0.75f)
            .background(LocalSurfaceFill.current, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides s.content) { content() }
    }
}

@Composable
fun AppChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalTheme.current
    if (!t.neumorph) {
        FilterChip(selected = selected, onClick = onClick, label = label, modifier = modifier)
        return
    }
    val s = t.card
    val shape = RoundedCornerShape(50)
    val bg = LocalSurfaceFill.current
    val deco = if (selected) {
        Modifier.background(bg, shape).insetSoft(s, shape, s.raise * 0.45f)
    } else {
        Modifier.raisedSoft(s, s.raise * 0.55f).background(bg, shape)
    }
    Box(
        modifier = modifier
            .then(deco)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        CompositionLocalProvider(
            LocalContentColor provides (if (selected) t.scheme.primary else s.content)
        ) {
            ProvideTextStyle(value = MaterialTheme.typography.labelLarge) { label() }
        }
    }
}

@Composable
fun AppField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    textStyle: TextStyle = LocalTextStyle.current
) {
    val t = LocalTheme.current
    if (!t.neumorph) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            label = label,
            placeholder = placeholder,
            textStyle = textStyle,
            singleLine = singleLine,
            minLines = minLines
        )
        return
    }
    // A label needs a straighter top edge to sit on, so only unlabelled fields go full pill.
    val shape = RoundedCornerShape(if (label == null) 50 else 18)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.insetSoft(t.card, shape, t.card.raise * 0.55f),
        enabled = enabled,
        label = label,
        placeholder = placeholder,
        textStyle = textStyle,
        singleLine = singleLine,
        minLines = minLines,
        shape = shape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent
        )
    )
}


