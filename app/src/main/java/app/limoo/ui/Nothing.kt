package app.limoo.ui

import android.app.Activity
import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.Canvas
import androidx.core.view.WindowCompat
import app.limoo.model.AppSettings

// ============================== theme ==============================

data class NColors(
    val bg: Color, val surface: Color, val surface2: Color, val line: Color,
    val text: Color, val dim: Color, val onText: Color, val accent: Color, val dark: Boolean,
)

val DarkN = NColors(Color(0xFF000000), Color(0xFF101010), Color(0xFF1B1B1B), Color(0xFF2A2A2A), Color(0xFFFFFFFF), Color(0xFF8A8A8A), Color(0xFF000000), Color(0xFFD71921), true)
val LightN = NColors(Color(0xFFF0F0F0), Color(0xFFFFFFFF), Color(0xFFE6E6E6), Color(0xFFD4D4D4), Color(0xFF0A0A0A), Color(0xFF78787A), Color(0xFFFFFFFF), Color(0xFFD71921), false)

/**
 * Accent roles. A single accent colour was too easy to miss on a near-black screen, so each theme also
 * tints the page background and surfaces slightly toward the accent. [mix] keeps surfaces recognisably
 * neutral (max 6% accent) so the minimal look survives.
 */
data class AccentSet(val base: Color, val bg: Color, val surface: Color, val surface2: Color, val line: Color)

private fun tint(base: Color, target: Color, amount: Float) = Color(
    red = base.red + (target.red - base.red) * amount,
    green = base.green + (target.green - base.green) * amount,
    blue = base.blue + (target.blue - base.blue) * amount,
    alpha = 1f,
)

val LocalN = staticCompositionLocalOf { DarkN }
val LocalHaptics = staticCompositionLocalOf { true }

object NType {
    val label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    val body = TextStyle(fontSize = 16.sp)
    val title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun NTheme(st: AppSettings, content: @Composable () -> Unit) {
    val dark = when (st.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val base = if (dark) DarkN else LightN
    val accent = when (st.accent) {
        "lime" -> if (dark) Color(0xFFB6D63B) else Color(0xFF5E7A00)
        "amber" -> if (dark) Color(0xFFFFB300) else Color(0xFFB37400)
        "blue" -> if (dark) Color(0xFF3D7EFF) else Color(0xFF0B4FC7)
        "mono" -> base.text
        else -> if (dark) Color(0xFFE5484D) else Color(0xFFB32B33)
    }
    // Surfaces drift a few percent toward the accent so the colour is felt across the whole UI, not
    // confined to a single dot. Line is mixed a little more so borders read as tinted, not grey.
    val n = if (st.accent == "mono") base.copy(accent = accent) else base.copy(
        accent = accent,
        bg = tint(base.bg, accent, if (dark) 0.045f else 0.030f),
        surface = tint(base.surface, accent, if (dark) 0.035f else 0.025f),
        surface2 = tint(base.surface2, accent, if (dark) 0.060f else 0.045f),
        line = tint(base.line, accent, if (dark) 0.090f else 0.070f),
    )
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val w = (view.context as Activity).window
        WindowCompat.setDecorFitsSystemWindows(w, false)
        w.statusBarColor = android.graphics.Color.TRANSPARENT; w.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
    }
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = n.text, onPrimary = n.onText, secondary = n.dim, background = n.bg, onBackground = n.text,
        surface = n.surface, onSurface = n.text, surfaceVariant = n.surface2, onSurfaceVariant = n.dim, outline = n.line, error = n.accent,
        surfaceContainer = n.surface, surfaceContainerHigh = n.surface, surfaceContainerHighest = n.surface2,
        surfaceContainerLow = n.bg, surfaceContainerLowest = n.bg,
    )
    CompositionLocalProvider(LocalN provides n, LocalHaptics provides st.haptics) { MaterialTheme(colorScheme = scheme, content = content) }
}

@Composable
fun rememberTick(strong: Boolean = false): () -> Unit {
    val h = LocalHapticFeedback.current; val on = LocalHaptics.current
    return remember(h, on, strong) { { if (on) h.performHapticFeedback(if (strong) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove) } }
}

// ============================== dot-matrix type ==============================

/** 5x7 dot-matrix font (variable width). One glyph per line: char followed by 7 rows of bits. */
private val GLYPHS: Map<Char, List<String>> = """
A 01110 10001 10001 11111 10001 10001 10001
B 11110 10001 10001 11110 10001 10001 11110
C 01110 10001 10000 10000 10000 10001 01110
D 11110 10001 10001 10001 10001 10001 11110
E 11111 10000 10000 11110 10000 10000 11111
F 11111 10000 10000 11110 10000 10000 10000
G 01110 10001 10000 10111 10001 10001 01111
H 10001 10001 10001 11111 10001 10001 10001
I 111 010 010 010 010 010 111
J 00111 00010 00010 00010 00010 10010 01100
K 10001 10010 10100 11000 10100 10010 10001
L 10000 10000 10000 10000 10000 10000 11111
M 10001 11011 10101 10101 10001 10001 10001
N 10001 11001 10101 10011 10001 10001 10001
O 01110 10001 10001 10001 10001 10001 01110
P 11110 10001 10001 11110 10000 10000 10000
Q 01110 10001 10001 10001 10101 10010 01101
R 11110 10001 10001 11110 10100 10010 10001
S 01111 10000 10000 01110 00001 00001 11110
T 11111 00100 00100 00100 00100 00100 00100
U 10001 10001 10001 10001 10001 10001 01110
V 10001 10001 10001 10001 10001 01010 00100
W 10001 10001 10001 10101 10101 11011 10001
X 10001 10001 01010 00100 01010 10001 10001
Y 10001 10001 01010 00100 00100 00100 00100
Z 11111 00001 00010 00100 01000 10000 11111
0 01110 10001 10011 10101 11001 10001 01110
1 010 110 010 010 010 010 111
2 01110 10001 00001 00010 00100 01000 11111
3 11110 00001 00001 01110 00001 00001 11110
4 00010 00110 01010 10010 11111 00010 00010
5 11111 10000 11110 00001 00001 10001 01110
6 00110 01000 10000 11110 10001 10001 01110
7 11111 00001 00010 00100 01000 01000 01000
8 01110 10001 10001 01110 10001 10001 01110
9 01110 10001 10001 01111 00001 00010 01100
. 0 0 0 0 0 0 1
, 0 0 0 0 0 1 1
: 0 1 0 0 0 1 0
- 0000 0000 0000 1111 0000 0000 0000
+ 00000 00100 00100 11111 00100 00100 00000
/ 00001 00010 00010 00100 01000 01000 10000
% 11001 11010 00010 00100 01000 01011 10011
! 1 1 1 1 1 0 1
? 01110 10001 00001 00010 00100 00000 00100
_ 00000 00000 00000 00000 00000 00000 11111
↓ 00100 00100 00100 10101 01110 00100 00000
↑ 00100 01110 10101 00100 00100 00100 00000
""".trimIndent().lines().filter { it.isNotBlank() }.associate { l -> val p = l.trim().split(" "); p[0][0] to p.drop(1) }

/**
 * Dot-matrix text. Drawn glyph-by-glyph so it needs no font file.
 *
 * [maxWidth] is the important part: without it a long string (a byte rate, a page title) computes a width
 * larger than the space it was given and gets clipped by the parent. When set, the dot pitch shrinks so the
 * whole string always fits the measured width; the row height follows the pitch so the aspect ratio stays
 * correct. Callers that render volatile values should pass a maxWidth so the layout never jumps.
 */
@Composable
fun DotText(
    text: String, modifier: Modifier = Modifier, dot: Dp = 3.dp, gap: Dp = 1.5.dp,
    color: Color = LocalN.current.text, spacing: Int = 1, maxWidth: Dp? = null,
) {
    val chars = remember(text) { text.uppercase().toList() }
    val widths = remember(chars) { chars.map { c -> if (c == ' ') 3 else (GLYPHS[c]?.first()?.length ?: 5) } }
    val cells = widths.sum() + spacing * (chars.size - 1).coerceAtLeast(0)
    val naturalCell = dot + gap
    val naturalW = naturalCell * cells - gap
    val naturalH = naturalCell * 7 - gap

    @Suppress("UNUSED_EXPRESSION") Box(modifier) {
        val limit = maxWidth
        val w = limit ?: naturalW
        // Shrink the pitch only when needed; never enlarge, so text is not scaled up past the caller's dot size.
        val cell = if (limit != null && naturalW > limit && cells > 0) (limit + gap) / cells else naturalCell
        val scale = cell / naturalCell
        Canvas(Modifier.size(w.coerceAtMost(if (limit != null) limit else naturalW), (naturalH * scale).coerceAtLeast(1.dp))) {
            val c = cell.toPx(); val r = (dot * scale).toPx() / 2
            var x = 0
            chars.forEachIndexed { i, ch ->
                GLYPHS[ch]?.forEachIndexed { row, bits ->
                    bits.forEachIndexed { col, b -> if (b == '1') drawCircle(color, r, Offset(x * c + col * c + r, row * c + r)) }
                }
                x += widths[i] + spacing
            }
        }
    }
}

/**
 * Dot-matrix text locked to a fixed box: the string is scaled to fit and the composable always occupies
 * exactly [width] x [height]. Use for live values (speed, counters, uptime) so the surrounding layout
 * never reflows as digits change.
 */
@Composable
fun DotTextFixed(
    text: String, width: Dp, height: Dp, modifier: Modifier = Modifier,
    color: Color = LocalN.current.text, align: Alignment = Alignment.CenterStart,
) {
    Box(modifier.width(width).height(height), contentAlignment = align) {
        DotText(text, dot = 2.dp, gap = 1.dp, color = color, maxWidth = width)
    }
}

/** Latency as five dots: more lit = faster. Timeout = single red dot. */
@Composable
fun DotMeter(ms: Long, testing: Boolean, modifier: Modifier = Modifier) {
    val n = LocalN.current
    val level = when { ms < 0 -> 0; ms == 0L -> 0; ms < 120 -> 5; ms < 250 -> 4; ms < 450 -> 3; ms < 800 -> 2; else -> 1 }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(5) { i ->
            val lit = !testing && i < level
            val c = when { testing -> n.dim.copy(alpha = if (i % 2 == 0) .6f else .2f); ms == 0L && i == 0 -> n.accent; lit -> n.text; else -> n.line }
            Box(Modifier.size(5.dp).clip(CircleShape).background(c))
        }
    }
}

/** Row of dots filled left to right (data usage, progress). */
@Composable
fun DotBar(fraction: Float, modifier: Modifier = Modifier, count: Int = 24) {
    val n = LocalN.current
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val step = size.width / count; val r = (step * 0.28f).coerceAtMost(size.height / 2)
        val lit = (fraction.coerceIn(0f, 1f) * count).toInt()
        for (i in 0 until count) drawCircle(if (i < lit) (if (fraction > .9f) n.accent else n.text) else n.line, r, Offset(i * step + step / 2, size.height / 2))
    }
}

// ============================== motion + feedback ==============================

/**
 * Loading indicator in the dot language: a ring of dots where one dot orbits, plus a sweeping arc of
 * fading dots. Deterministic phases ([phase] in 0f..1f) so callers can drive it from a progress value
 * instead of an infinite animation when they have one.
 */
@Composable
fun NSpinner(modifier: Modifier = Modifier, dots: Int = 12, phase: Float? = null, color: Color = LocalN.current.accent) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(phase) {
        if (phase != null) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t = ((it - start) % 1_200_000_000L) / 1_200_000_000f }
    }
    Canvas(modifier.size(26.dp)) {
        val p = phase ?: t
        val c = center; val r = size.minDimension / 2 - 3.dp.toPx(); val dotR = 1.7.dp.toPx()
        for (i in 0 until dots) {
            val f = i.toFloat() / dots
            val d = ((f - p) % 1f + 1f) % 1f
            val a = (if (d < .45f) 1f - d / .45f else 0f).coerceIn(0f, 1f)
            val ang = f * 2f * PI.toFloat() - PI.toFloat() / 2f
            drawCircle(color.copy(alpha = .12f + a * .88f), dotR, Offset(c.x + r * cos(ang), c.y + r * sin(ang)))
        }
    }
}

/** Inline busy row: spinner + label. Shown while an import or subscription fetch runs. */
@Composable
fun NBusy(label: String, modifier: Modifier = Modifier, detail: String = "") {
    val n = LocalN.current
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        NSpinner()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label.uppercase(), style = NType.label, color = n.text)
            if (detail.isNotEmpty()) Text(detail.uppercase(), style = NType.label, color = n.dim, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Full-sheet loading state used by import and subscription work. */
@Composable
fun NBusyBlock(label: String, detail: String = "") {
    val n = LocalN.current
    Column(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        NSpinner(Modifier.size(34.dp), dots = 16)
        Spacer(Modifier.height(16.dp))
        Text(label.uppercase(), style = NType.label, color = n.text)
        if (detail.isNotEmpty()) Text(detail.uppercase(), style = NType.label, color = n.dim, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * The signature element: a dot-matrix glyph rendered in place of an icon. This is the Nothing idea that
 * matters most for this app - icons become type. Use it for list affordances and section markers.
 */
@Composable
fun NGlyph(glyph: String, modifier: Modifier = Modifier, dot: Dp = 2.dp, color: Color = LocalN.current.dim) {
    DotText(glyph, modifier, dot = dot, gap = dot / 2, color = color, spacing = 1)
}

/** Vertical dot column used as a quiet "there is more below" hint at the end of a list. */
@Composable
fun NFadeDots(count: Int = 3, modifier: Modifier = Modifier, color: Color = LocalN.current.line) {
    Canvas(modifier.width(6.dp).height((count * 7).dp)) {
        val r = 1.5.dp.toPx()
        for (i in 0 until count) {
            drawCircle(color.copy(alpha = 0.8f - i * 0.22f), r, Offset(size.width / 2, r + i * 6.dp.toPx()))
        }
    }
}

/**
 * Dot-matrix readout with a label, used where a number matters and should feel instrumented rather than
 * typeset. Width-bounded so it cannot overflow its row.
 */
@Composable
fun NReadout(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = LocalN.current.text, width: Dp = 140.dp) {
    val n = LocalN.current
    Column(modifier) {
        NLabel(label)
        Spacer(Modifier.height(6.dp))
        DotTextFixed(value.uppercase(), width, 14.dp, color = valueColor)
    }
}

/** Thin progress rule made of dots - the app's alternative to a Material linear indicator. */
@Composable
fun NDotsProgress(fraction: Float, modifier: Modifier = Modifier, count: Int = 28, color: Color = LocalN.current.accent) {
    val n = LocalN.current
    val lit = (fraction.coerceIn(0f, 1f) * count).toInt()
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val step = size.width / count; val r = (step * 0.3f).coerceAtMost(size.height / 2)
        for (i in 0 until count) drawCircle(if (i < lit) color else n.line, r, Offset(i * step + step / 2, size.height / 2))
    }
}

// ============================== structure ==============================

/**
 * Section marker: a hairline that fades out with a label sitting on it. Used instead of a plain label so
 * screens have visible structure without adding chrome-heavy headers.
 */
@Composable
fun NRule(label: String = "", modifier: Modifier = Modifier) {
    val n = LocalN.current
    Row(modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        NLabel(label, color = n.dim)
        if (label.isNotEmpty()) Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(n.line))
    }
}

/** Three vertical dots. The row overflow affordance - drawn, not typeset, to match the dot language. */
@Composable
fun NDots(onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = LocalN.current.dim) {
    val tick = rememberTick()
    Box(modifier.size(40.dp).clip(CircleShape).clickable { tick(); onClick() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(4.dp, 16.dp)) {
            val r = 1.6.dp.toPx()
            for (i in 0 until 3) drawCircle(color, r, Offset(size.width / 2, r + i * (size.height / 3f)))
        }
    }
}

/** Corner ticks - a framing device that reads as instrumentation rather than decoration. */
@Composable
fun NBrackets(modifier: Modifier = Modifier, size: Dp = 10.dp, color: Color = LocalN.current.line) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val t = 1.5.dp.toPx()
        listOf(
            Offset(0f, t / 2) to Offset(w, t / 2), Offset(0f, t / 2) to Offset(t / 2, t / 2),
            Offset(0f, h - t / 2) to Offset(w, t - t / 2), Offset(0f, h - t / 2) to Offset(t / 2, h - t / 2),
            Offset(w, t / 2) to Offset(w - t / 2, t / 2), Offset(w, h - t / 2) to Offset(w - t / 2, h - t / 2),
        ).forEach { (a, b) -> drawLine(color, a, b, t) }
    }
}

/**
 * Subscription allowance readout: how much data is used out of the total, plus days left or days since
 * expiry. Values come from the provider's subscription-userinfo header. Returns null when the provider
 * does not report a quota, so callers can simply skip the row.
 */
@Composable
fun SubAllowance(name: String, up: Long, down: Long, total: Long, expire: Long, modifier: Modifier = Modifier) {
    val n = LocalN.current
    if (total <= 0 && expire <= 0) return
    val used = up + down
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NLabel(name, Modifier.weight(1f))
            if (total > 0) NLabel("${fmtBytesShort(used)} / ${fmtBytesShort(total)}")
        }
        if (total > 0) DotBar(used.toFloat() / total, Modifier.padding(top = 8.dp))
        if (expire > 0) {
            val days = (expire - System.currentTimeMillis() / 1000) / 86_400L
            NLabel(
                when { days < 0 -> "EXPIRED ${-days} DAYS AGO"; days == 0L -> "EXPIRES TODAY"; else -> "$days DAYS LEFT" },
                Modifier.padding(top = 8.dp), color = if (days < 3) n.accent else n.dim,
            )
        }
    }
}

/** Compact byte size for quota lines (no trailing space before the unit). */
fun fmtBytesShort(b: Long): String {
    val u = arrayOf("B", "K", "M", "G", "T"); var v = b.coerceAtLeast(0).toDouble(); var i = 0
    while (v >= 1024 && i < 4) { v /= 1024; i++ }
    return (if (i == 0) "%.0f" else "%.1f").format(v) + u[i]
}

/** Small key/value readout used in cards; label in mono caps, value in the normal face. */
@Composable
fun NStat(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = LocalN.current.text) {
    val n = LocalN.current
    Column(modifier) {
        NLabel(label)
        Text(value.uppercase(), style = NType.mono, color = valueColor, modifier = Modifier.padding(top = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ============================== components ==============================

@Composable fun NLabel(text: String, modifier: Modifier = Modifier, color: Color = LocalN.current.dim) =
    Text(text.uppercase(), modifier, color = color, style = NType.label)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NCard(
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null,
    highlight: Boolean = false, radius: Dp = 28.dp, content: @Composable ColumnScope.() -> Unit,
) {
    val n = LocalN.current; val shape = RoundedCornerShape(radius)
    val base = modifier.clip(shape).background(n.surface, shape).border(1.dp, if (highlight) n.text else n.line, shape)
    Column(if (onClick != null || onLongClick != null) base.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick) else base, content = content)
}

@Composable
fun NButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, danger: Boolean = false, compact: Boolean = false) {
    val n = LocalN.current; val tick = rememberTick()
    val fg = when { primary -> n.onText; danger -> n.accent; else -> n.text }
    Box(
        modifier.height(if (compact) 34.dp else 46.dp).alpha(if (enabled) 1f else .35f).clip(CircleShape)
            .background(if (primary) n.text else Color.Transparent).border(1.dp, if (primary) n.text else if (danger) n.accent else n.line, CircleShape)
            .clickable(enabled = enabled) { tick(); onClick() }.padding(horizontal = if (compact) 14.dp else 22.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text.uppercase(), style = NType.label, color = fg) }
}

@Composable
fun NChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val n = LocalN.current
    Box(
        Modifier.clip(CircleShape).background(if (selected) n.text else Color.Transparent).border(1.dp, if (selected) n.text else n.line, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text.uppercase(), style = NType.label, color = if (selected) n.onText else n.dim, maxLines = 1) }
}

@Composable
fun NSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val n = LocalN.current; val tick = rememberTick()
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, tween(140), label = "sw")
    Box(
        Modifier.size(48.dp, 28.dp).clip(CircleShape).background(if (checked) n.text else n.surface2)
            .border(1.dp, if (checked) n.text else n.line, CircleShape).clickable { tick(); onChange(!checked) },
    ) { Box(Modifier.offset(x = x, y = 2.dp).size(22.dp).clip(CircleShape).background(if (checked) n.onText else n.dim)) }
}

@Composable
fun NCheck(checked: Boolean, modifier: Modifier = Modifier) {
    val n = LocalN.current
    Box(modifier.size(22.dp).clip(CircleShape).background(if (checked) n.text else Color.Transparent).border(1.dp, if (checked) n.text else n.dim, CircleShape), contentAlignment = Alignment.Center) {
        if (checked) Box(Modifier.size(8.dp).clip(CircleShape).background(n.onText))
    }
}

@Composable
fun NRadio(active: Boolean, modifier: Modifier = Modifier) {
    val n = LocalN.current
    Box(modifier.size(20.dp).clip(CircleShape).border(1.dp, if (active) n.accent else n.line, CircleShape), contentAlignment = Alignment.Center) {
        if (active) Box(Modifier.size(10.dp).clip(CircleShape).background(n.accent))
    }
}

@Composable fun NDivider() = Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(LocalN.current.line))

@Composable
fun NRow(title: String, sub: String = "", onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val n = LocalN.current
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = NType.body, color = n.text)
            if (sub.isNotEmpty()) Text(sub.uppercase(), style = NType.label, color = n.dim, modifier = Modifier.padding(top = 3.dp))
        }
        trailing?.invoke()
    }
}

@Composable
fun NField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    multiline: Boolean = false, password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, placeholder: String = "",
) {
    val n = LocalN.current
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (label.isNotEmpty()) NLabel(label)
        Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, style = NType.mono.copy(fontSize = 15.sp), color = n.dim.copy(alpha = .5f))
            BasicTextField(
                value, onChange, Modifier.fillMaxWidth(), singleLine = !multiline, minLines = if (multiline) 3 else 1,
                textStyle = NType.mono.copy(color = n.text, fontSize = 15.sp), cursorBrush = SolidColor(n.accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            )
        }
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(1.dp).background(n.line))
    }
}

/** Search pill. */
@Composable
fun NSearch(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val n = LocalN.current
    Box(modifier.fillMaxWidth().height(46.dp).clip(CircleShape).background(n.surface).border(1.dp, n.line, CircleShape).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) NLabel("SEARCH")
        BasicTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true, textStyle = NType.mono.copy(color = n.text, fontSize = 14.sp), cursorBrush = SolidColor(n.accent))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val n = LocalN.current
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = n.surface, contentColor = n.text,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { Box(Modifier.padding(vertical = 12.dp).size(36.dp, 4.dp).clip(CircleShape).background(n.line)) },
    ) { Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding().imePadding(), content = content) }
}

/** Tappable list row for sheets. */
@Composable
fun SheetRow(title: String, sub: String = "", danger: Boolean = false, highlight: Boolean = false, onClick: () -> Unit) {
    val n = LocalN.current; val tick = rememberTick()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { tick(); onClick() }.padding(horizontal = 4.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (highlight) n.accent else Color.Transparent))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = NType.body, color = if (danger) n.accent else n.text)
            if (sub.isNotEmpty()) Text(sub.uppercase(), style = NType.label, color = n.dim, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Setting row that opens a sheet of options. */
@Composable
fun ChoiceRow(title: String, options: List<String>, value: String, onPick: (String) -> Unit) {
    val n = LocalN.current; var open by remember { mutableStateOf(false) }
    NRow(title, onClick = { open = true }, trailing = { Text(value.ifEmpty { "none" }.uppercase(), style = NType.label, color = n.text) })
    if (open) NSheet({ open = false }) {
        NLabel(title, Modifier.padding(bottom = 8.dp))
        options.forEach { o -> SheetRow(o.ifEmpty { "none" }.uppercase(), highlight = o == value) { onPick(o); open = false } }
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, sub: String = "", onChange: (Boolean) -> Unit) =
    NRow(title, sub, onClick = { onChange(!checked) }, trailing = { NSwitch(checked, onChange) })

/** Inline text setting; keeps its own text state so typing never fights the store. */
@Composable
fun FieldRow(title: String, value: String, multiline: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    var t by remember(title) { mutableStateOf(value) }
    NField(title, t, { t = it; onChange(it) }, Modifier.padding(horizontal = 20.dp), multiline = multiline, keyboard = keyboard)
}

@Composable
fun NumberRow(title: String, value: Int, onChange: (Int) -> Unit) =
    FieldRow(title, value.toString(), keyboard = KeyboardType.Number) { v -> v.toIntOrNull()?.let(onChange) }
