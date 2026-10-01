package app.limoo.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens for the Nothing-inspired language. Implements docs/NOTHING-UI-SPEC.md.
 *
 * The governing rule from that spec: the identity must NOT collapse into the dot font. It comes from the
 * grid, the spacing scale, monochrome surfaces, hairlines, restrained geometry and typography hierarchy.
 * Dot-matrix type is one instrument among many, reserved for counters and technical readouts.
 *
 * Nothing here is a Material default. The app must not look like Material wearing a black background.
 */

// ---------------------------------------------------------------- palette

data class NColors(
    val bg: Color, val surface: Color, val surface2: Color,
    val line: Color, val lineStrong: Color,
    val text: Color, val dim: Color, val muted: Color, val onText: Color,
    val accent: Color,          // the signal - used sparingly
    val dark: Boolean,
)

/** Classic dark: OLED-black canvas, three surface steps, two line weights, three text steps, one signal. */
val DarkN = NColors(
    bg = Color(0xFF0A0A0A),
    surface = Color(0xFF111111),
    surface2 = Color(0xFF171717),
    line = Color(0xFF252525),
    lineStrong = Color(0xFF373737),
    text = Color(0xFFF2F2F2),
    dim = Color(0xFFA0A0A0),
    muted = Color(0xFF6B6B6B),
    onText = Color(0xFF000000),
    accent = Color(0xFFD71920),
    dark = true,
)

/** Light: warm off-white canvas. Same relationships, inverted. */
val LightN = NColors(
    bg = Color(0xFFFAFAF8),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF1F1EF),
    line = Color(0xFFE2E2DE),
    lineStrong = Color(0xFFC9C9C4),
    text = Color(0xFF0A0A0A),
    dim = Color(0xFF6B6B6B),
    muted = Color(0xFF9A9A96),
    onText = Color(0xFFFFFFFF),
    accent = Color(0xFFC8102E),
    dark = false,
)

// ---------------------------------------------------------------- spacing
// One base unit (4dp), everything derived. No arbitrary values in screens.

object Space {
    val micro = 4.dp      // between a label and its value
    val compact = 8.dp    // between related rows
    val small = 12.dp     // inside a compact component
    val standard = 16.dp  // card internal padding
    val card = 24.dp      // card / section padding
    val section = 32.dp   // between major blocks
    val major = 48.dp     // between sections
    val hero = 64.dp      // around a hero element
    val editorial = 96.dp // deliberate empty zones
}

/** Small radii only. A square technical panel beside a softly rounded one reads better than uniformity. */
object Radius {
    val none = 0.dp       // technical containers, separators
    val control = 4.dp    // buttons, chips, switches
    val card = 8.dp       // normal cards
    val raised = 12.dp    // larger elevated surfaces
    val pill = 999.dp     // dots and circular controls only - never content containers
}

// ---------------------------------------------------------------- typography
// Body and headings use the platform sans. Monospace is for compact technical metadata only.
// Hierarchy is carried by size and position, not by weight or colour.

object NType {
    /** Page heading. Large, sans, light tracking. */
    val display = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.5).sp)

    /** Section heading. */
    val title = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp)

    /** Body copy. Fully readable, sentence case, never uppercase. */
    val body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp)

    val bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp)

    /** UI label. Sans, medium - used for values and control text. */
    val label = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)

    /** Micro label: uppercase, tracked out, monospace. Metadata and categories only. */
    val micro = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.1.sp, fontWeight = FontWeight.Medium)

    /** Technical readout in mono, for values that sit next to a micro label. */
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
}
