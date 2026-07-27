/*
 * App theme (UI/UX look-and-feel pass, follow-up to #1).
 *
 * Colors are pulled directly from the project's existing brand identity
 * (docs/branding/generate_logo.py: BG_COLOR/FG_COLOR/GLYPH_COLOR — the same
 * near-black background and PPG-green accent used in the app icon, banner,
 * and social preview), rather than introducing a separate app palette. The
 * app is dark-themed unconditionally (not following system light/dark) to
 * match that identity consistently.
 *
 * Typography pairs the default system sans for prose/labels/buttons with
 * JetBrains Mono (already the brand's wordmark font, bundled under
 * res/font/, SIL OFL 1.1 — see docs/branding/assets/fonts/JetBrainsMono-OFL.txt)
 * for numeric/technical readouts: sample counters, Hz values, HR, session
 * sizes. Keeps the "technical instrument" feel for data without making
 * every label in the app monospace.
 */

package com.polarppgbp.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polarppgbp.R

// ---- brand colors (source of truth: docs/branding/generate_logo.py) ----

val BrandBackground = Color(0xFF0D1210)
val BrandSurface = Color(0xFF161C19)
val BrandSurfaceVariant = Color(0xFF20281F)
val BrandGreen = Color(0xFF1EFA8C)
val BrandSage = Color(0xFF5A7A6E)
val BrandAmber = Color(0xFFF9A825)
val BrandRed = Color(0xFFFF6B6B)
val BrandOutline = Color(0xFF3A453F)

val AppColorScheme = darkColorScheme(
    background = BrandBackground,
    surface = BrandSurface,
    surfaceVariant = BrandSurfaceVariant,
    primary = BrandGreen,
    onPrimary = BrandBackground,
    secondary = BrandSage,
    onSecondary = BrandBackground,
    onBackground = Color(0xFFE4EAE7),
    onSurface = Color(0xFFE4EAE7),
    onSurfaceVariant = Color(0xFFB7C2BC),
    outline = BrandOutline,
    error = BrandRed,
    onError = BrandBackground,
)

/** Semantic status colors for the recorder Phase indicator — not part of
 * Material3's role set, so kept as a small separate palette rather than
 * overloading e.g. `error` for "reconnecting" and `primary` for "capturing"
 * (capturing does reuse primary deliberately: it *is* the "all good" state). */
object StatusColors {
    val Stopped = BrandSage
    val Connecting = BrandAmber
    val Capturing = BrandGreen
    val Reconnecting = BrandRed

    /** #17: a hard blocker is worse than a dropped link -- it will not self-heal. */
    val Blocked = BrandRed
}

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** Monospace text style for numeric/technical readouts (sample counters,
 * Hz values, HR, session sizes) — use via `MaterialTheme.typography` copies
 * or directly, e.g. `Text(count, style = MonoReadout)`. */
val MonoReadout = TextStyle(
    fontFamily = JetBrainsMono,
    fontWeight = FontWeight.Bold,
    fontSize = 16.sp,
    letterSpacing = 0.2.sp,
)

val MonoReadoutLarge = MonoReadout.copy(fontSize = 24.sp)

private val baseTypography = Typography()

val AppTypography = baseTypography.copy(
    headlineMedium = baseTypography.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = baseTypography.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleSmall = baseTypography.titleSmall.copy(fontWeight = FontWeight.Bold),
)

val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

/** Consistent spacing scale, in place of ad-hoc `.dp` literals scattered
 * through the screens. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 14.dp
    val lg = 20.dp
    val xl = 32.dp

    /** Gap between adjacent tappable buttons, wider than [md]. A patient with
     * mobility/dexterity difficulty is more likely to mis-tap a neighbouring
     * button the closer two targets sit, so button rows use this instead of the
     * general layout spacing. */
    val buttonGap = 24.dp

    /** Minimum touch target height, above Android's own 48dp accessibility
     * minimum, for the same reason as [buttonGap]. */
    val minTouchTarget = 56.dp
}

@Composable
fun PpgBpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
