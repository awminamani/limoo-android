package app.limoo.ui

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.limoo.model.AppSettings
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ============================== theme ==============================

val LocalN = staticCompositionLocalOf { DarkN }
val LocalHaptics = staticCompositionLocalOf { true }

@Composable
fun NTheme(st: AppSettings, content: @Composable () -> Unit) {
    val dark = when (st.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val base = if (dark) DarkN else LightN
    val accent = when (st.accent) {
        "red" -> base.accent
        "lime" -> if (dark) Color(0xFFB6D63B) else Color(0xFF5E7A00)
        "blue" -> if (dark) Color(0xFF5B8DEF) else Color(0xFF2C5FCC)
        "amber" -> if (dark) Color(0xFFF5A623) else Color(0xFFB36B00)
        // "mono" means the interface carries no signal colour at all - inversion does the work.
        else -> base.text
    }
    // Cross-fade the accent instead of snapping. An abrupt switch made the wallpaper tint jump, which
    // read as a glitch; a short ease keeps it calm and is barely perceptible as an animation.
    val accentAnim by animateColorAsState(accent, tween(320), label = "accent")
    val n = base.copy(accent = accentAnim)
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val w = (view.context as Activity).window
        WindowCompat.setDecorFitsSystemWindows(w, false)
        w.statusBarColor = android.graphics.Color.TRANSPARENT
        w.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(w, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    // Material is used purely as a structural primitive; the scheme is overwritten wholesale so no default
    // Material surface, ripple or colour leaks through.
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = n.text, onPrimary = n.onText, secondary = n.dim, onSecondary = n.onText,
        background = n.bg, onBackground = n.text,
        surface = n.surface, onSurface = n.text,
        surfaceVariant = n.surface2, onSurfaceVariant = n.dim,
        surfaceContainerLowest = n.bg, surfaceContainerLow = n.bg,
        surfaceContainer = n.surface, surfaceContainerHigh = n.surface, surfaceContainerHighest = n.surface2,
        outline = n.line, outlineVariant = n.line, error = n.accent, onError = n.onText,
    )
    CompositionLocalProvider(LocalN provides n, LocalHaptics provides st.haptics) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
fun rememberTick(strong: Boolean = false): () -> Unit {
    val h = LocalHapticFeedback.current
    val on = LocalHaptics.current
    return remember(h, on, strong) {
        { if (on) h.performHapticFeedback(if (strong) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove) }
    }
}

// ============================== dot-matrix type ==============================
// A 5x7 dot display, drawn on Canvas so it needs no font file. Per the spec this is an INSTRUMENT, not the
// app's voice: it is used for counters, timers and technical readouts, never for body text or navigation.

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
""".trimIndent().lines().filter { it.isNotBlank() }.associate { l -> val p = l.trim().split(" "); p[0][0] to p.drop(1) }

/**
 * Dot-matrix readout. The pitch shrinks to fit [maxWidth] so a changing value never reflows the layout or
 * overflows its slot - essential for live counters, which change every second.
 */
@Composable
fun DotReadout(
    text: String, width: Dp, modifier: Modifier = Modifier,
    color: Color = LocalN.current.text,
    /** Cap on the dot pitch. A long string shrinks instead of overflowing its slot. */
    maxPitch: Dp = 4.dp,
) {
    val chars = remember(text) { text.uppercase().toList() }
    val widths = remember(chars) { chars.map { c -> if (c == ' ') 3 else (GLYPHS[c]?.first()?.length ?: 5) } }
    val cells = widths.sum() + (chars.size - 1).coerceAtLeast(0)

    val gap = 1.dp
    // The pitch is bounded so short strings are not blown up to absurd dot sizes, and so long ones shrink.
    val pitch = if (cells > 0) ((width - gap) / cells).coerceAtMost(maxPitch) else maxPitch
    // A glyph is exactly 7 rows tall. The height MUST be derived from the pitch - sizing the Canvas to a
    // fixed smaller height clips the bottom rows and every character renders chopped. This was the cause of
    // the garbled-looking speed figures: a 148dp slot needs 26dp of height, not 20dp.
    val height = pitch * 7

    Canvas(modifier.width(width).height(height)) {
        if (cells <= 0) return@Canvas
        val cell = pitch.toPx()
        val g = gap.toPx()
        val r = (cell - g) / 2f
        var x = 0
        chars.forEachIndexed { i, ch ->
            GLYPHS[ch]?.forEachIndexed { row, bits ->
                bits.forEachIndexed { col, b -> if (b == '1') drawCircle(color, r, Offset(x + col * cell + cell / 2, row * cell + cell / 2)) }
            }
            x += widths[i] + 1
        }
    }
}

// ============================== micrographics ==============================
// Instrument-like graphics. Each one carries information; none is decoration for its own sake.

/** Latency as a five-segment meter. Segments, not a smooth bar - it reads as hardware. */
@Composable
fun DotMeter(ms: Long, testing: Boolean, modifier: Modifier = Modifier) {
    val n = LocalN.current
    val level = when { ms < 0 || ms == 0L -> 0; ms < 120 -> 5; ms < 250 -> 4; ms < 450 -> 3; ms < 800 -> 2; else -> 1 }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(5) { i ->
            val c = when {
                testing -> n.line
                ms == 0L && i == 0 -> n.accent
                i < level -> n.text
                else -> n.line
            }
            Box(Modifier.size(width = 8.dp, height = 3.dp).background(c))
        }
    }
}

/** Segmented capacity bar. Full turns to the signal colour so exhaustion is unmissable. */
@Composable
fun SegmentedBar(fraction: Float, modifier: Modifier = Modifier, segments: Int = 28, height: Dp = 6.dp) {
    val n = LocalN.current
    val lit = (fraction.coerceIn(0f, 1f) * segments).toInt()
    val signal = fraction > 0.9f
    Canvas(modifier.fillMaxWidth().height(height)) {
        val gap = 2.dp.toPx()
        val segW = (size.width - gap * (segments - 1)) / segments
        for (i in 0 until segments) {
            // DrawScope has no Paint; pass the colour straight to drawRect.
            val c = if (i < lit) { if (signal) n.accent else n.text } else n.line
            drawRect(c, Offset(i * (segW + gap), 0f), androidx.compose.ui.geometry.Size(segW, size.height))
        }
    }
}

/** Loading as a technical signal: a segment filling, then wiping, rather than a generic spinner. */
@Composable
fun SignalLoader(active: Boolean, modifier: Modifier = Modifier, segments: Int = 12) {
    val n = LocalN.current
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t = ((it - start) % 1_400_000_000L) / 1_400_000_000f }
    }
    Canvas(modifier.width(76.dp).height(8.dp)) {
        if (!active) {
            repeat(segments) { i ->
                drawRect(n.line, Offset(i * ((size.width) / segments), 0f), androidx.compose.ui.geometry.Size((size.width / segments) - 2.dp.toPx(), size.height))
            }
            return@Canvas
        }
        val gap = 2.dp.toPx()
        val segW = (size.width - gap * (segments - 1)) / segments
        val head = (t * segments).toInt()
        for (i in 0 until segments) {
            val d = ((i - head) % segments + segments) % segments
            val a = 1f - d / segments.toFloat()
            drawRect(n.text.copy(alpha = 0.12f + a * 0.88f), Offset(i * (segW + gap), 0f), androidx.compose.ui.geometry.Size(segW, size.height))
        }
    }
}

/**
 * Overflow affordance: three short horizontal rules, the conventional technical menu mark. Drawn rather
 * than typeset, and deliberately NOT dots - the spec is explicit that not every icon should be dot-rendered.
 */
@Composable
fun MenuMark(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val n = LocalN.current
    val tick = rememberTick()
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(Radius.control))
            .clickable { tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.width(14.dp).height(10.dp)) {
            val t = 1.4.dp.toPx()
            for (i in 0 until 3) drawRect(n.dim, Offset(0f, i * 4.dp.toPx()), androidx.compose.ui.geometry.Size(size.width, t))
        }
    }
}

/** Single signal dot - the smallest unit of status in this language. */
@Composable
fun SignalDot(active: Boolean, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    val n = LocalN.current
    Box(modifier.size(size).background(if (active) n.accent else n.lineStrong, RoundedCornerShape(Radius.pill)))
}

/** Calibration ticks - framing that reads as instrument panel, not ornament. */
@Composable
fun Ticks(modifier: Modifier = Modifier, count: Int = 12, height: Dp = 6.dp) {
    val n = LocalN.current
    Canvas(modifier.fillMaxWidth().height(height)) {
        val step = size.width / count
        for (i in 0 until count) {
            val long = i % 3 == 0
            drawRect(n.line, Offset(i * step, 0f), androidx.compose.ui.geometry.Size(1.dp.toPx(), if (long) size.height else size.height * 0.5f))
        }
    }
}

/** Sparse dot constellation for empty states. Deliberately quiet. */
@Composable
fun DotField(modifier: Modifier = Modifier, seed: Int = 7) {
    val n = LocalN.current
    Canvas(modifier) {
        val cols = 9; val rows = 5
        val cw = size.width / cols; val ch = size.height / rows
        // A fixed sparse pattern - deterministic so the empty state does not shimmer between recompositions.
        val pts = listOf(1 to 0, 4 to 0, 7 to 1, 2 to 2, 5 to 2, 0 to 3, 3 to 3, 6 to 3, 8 to 4)
        pts.forEach { (x, y) ->
            val on = ((x * 7 + y * 13 + seed) % 5) == 0
            drawCircle(if (on) n.lineStrong else n.line, 1.5.dp.toPx(), Offset(cw * (x + 0.5f), ch * (y + 0.5f)))
        }
    }
}

// ============================== components ==============================
// Depth comes from spacing, hairlines and scale. No shadows, no gradients, no glassmorphism.

/** Micro label: uppercase, tracked, monospace. Categories and metadata only. */
@Composable
fun NLabel(text: String, modifier: Modifier = Modifier, color: Color = LocalN.current.muted) =
    Text(text.uppercase(), modifier, color = color, style = NType.micro, maxLines = 1, overflow = TextOverflow.Ellipsis)

/** A hairline rule. Optional label sits above it, left-aligned to the grid. */
@Composable
fun NRule(label: String = "", modifier: Modifier = Modifier) {
    val n = LocalN.current
    Column(modifier.fillMaxWidth().padding(top = Space.section, bottom = Space.small)) {
        if (label.isNotEmpty()) NLabel(label)
        Spacer(Modifier.height(Space.small))
        Box(Modifier.fillMaxWidth().height(1.dp).background(n.line))
    }
}

/**
 * The one card. A hairline border on a barely-lifted surface, 8dp radius, generous internal padding.
 * `highlight` swaps the hairline to the strong weight - it does not fill or glow.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NCard(
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null,
    highlight: Boolean = false, radius: Dp = Radius.card, content: @Composable ColumnScope.() -> Unit,
) {
    val n = LocalN.current
    val shape = RoundedCornerShape(radius)
    val base = modifier
        .clip(shape)
        .background(n.surface, shape)
        .border(BorderStroke(1.dp, if (highlight) n.lineStrong else n.line), shape)
    Column(
        if (onClick != null || onLongClick != null)
            base.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
        else base,
        content = content,
    )
}

/**
 * Primary action: light surface, dark text, minimal radius, strong contrast. Selected/active states use
 * this inversion rather than the signal colour - the spec is explicit that not every button is red.
 */
@Composable
fun NButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    primary: Boolean = false, enabled: Boolean = true, danger: Boolean = false, compact: Boolean = false,
) {
    val n = LocalN.current
    val tick = rememberTick()
    val fg = when { primary -> n.onText; danger -> n.accent; else -> n.text }
    val border = when { primary -> n.text; danger -> n.accent; else -> n.line }
    Box(
        modifier
            .height(if (compact) 32.dp else 44.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(RoundedCornerShape(Radius.control))
            .background(if (primary) n.text else Color.Transparent)
            .border(BorderStroke(1.dp, border), RoundedCornerShape(Radius.control))
            .clickable(enabled = enabled) { tick(); onClick() }
            .padding(horizontal = if (compact) 12.dp else 20.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text.uppercase(), style = NType.micro, color = fg, maxLines = 1) }
}

/** Filter chip. Selected = inversion, never the signal colour. */
@Composable
fun NChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val n = LocalN.current
    val tick = rememberTick()
    val shape = RoundedCornerShape(Radius.control)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) n.text else Color.Transparent)
            .border(BorderStroke(1.dp, if (selected) n.text else n.line), shape)
            .clickable { tick(); onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(text.uppercase(), style = NType.micro, color = if (selected) n.onText else n.dim, maxLines = 1) }
}

/** Switch: black/white inversion. An "on" switch is not red. */
@Composable
fun NSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val n = LocalN.current
    val tick = rememberTick()
    val x by animateDpAsState(if (checked) 20.dp else 2.dp, tween(160), label = "sw")
    Box(
        Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(Radius.control))
            .background(if (checked) n.text else n.surface2)
            .border(BorderStroke(1.dp, if (checked) n.text else n.line), RoundedCornerShape(Radius.control))
            .clickable { tick(); onChange(!checked) },
    ) { Box(Modifier.offset(x = x, y = 3.dp).size(20.dp).background(if (checked) n.onText else n.dim, RoundedCornerShape(2.dp))) }
}

@Composable
fun NCheck(checked: Boolean, modifier: Modifier = Modifier) {
    val n = LocalN.current
    Box(
        modifier.size(20.dp).clip(RoundedCornerShape(2.dp))
            .background(if (checked) n.text else Color.Transparent)
            .border(BorderStroke(1.dp, if (checked) n.text else n.lineStrong), RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) { if (checked) Box(Modifier.size(7.dp).background(n.onText)) }
}

@Composable
fun NRadio(active: Boolean, modifier: Modifier = Modifier) {
    val n = LocalN.current
    Box(
        modifier.size(18.dp).border(BorderStroke(1.dp, if (active) n.text else n.lineStrong), RoundedCornerShape(Radius.pill)),
        contentAlignment = Alignment.Center,
    ) { if (active) Box(Modifier.size(8.dp).background(n.text, RoundedCornerShape(Radius.pill))) }
}

/** Row: title, optional supporting line, optional trailing content. Reads as a list line, not a card. */
@Composable
fun NRow(
    title: String, sub: String = "", onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val n = LocalN.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.card, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = NType.body, color = n.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) {
                Text(sub.uppercase(), style = NType.micro, color = n.muted, modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun NDivider(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().padding(start = Space.card).height(1.dp).background(LocalN.current.line))

@Composable
fun NField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    multiline: Boolean = false, password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text,
    placeholder: String = "",
) {
    val n = LocalN.current
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (label.isNotEmpty()) NLabel(label)
        Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, style = NType.mono.copy(fontSize = 15.sp), color = n.muted)
            }
            BasicTextField(
                value, onChange, Modifier.fillMaxWidth(),
                singleLine = !multiline, minLines = if (multiline) 3 else 1,
                textStyle = NType.mono.copy(color = n.text, fontSize = 15.sp),
                cursorBrush = SolidColor(n.text),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(n.line))
    }
}

@Composable
fun NSearch(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val n = LocalN.current
    Box(
        modifier.fillMaxWidth().height(44.dp)
            .clip(RoundedCornerShape(Radius.control))
            .background(n.surface)
            .border(BorderStroke(1.dp, n.line), RoundedCornerShape(Radius.control))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text("Search", style = NType.body, color = n.muted)
        BasicTextField(
            value, onChange, Modifier.fillMaxWidth(), singleLine = true,
            textStyle = NType.body.copy(color = n.text), cursorBrush = SolidColor(n.text),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val n = LocalN.current
    ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = n.surface, contentColor = n.text,
        shape = RoundedCornerShape(topStart = Radius.raised, topEnd = Radius.raised),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { Box(Modifier.padding(vertical = 10.dp).width(32.dp).height(1.dp).background(n.lineStrong)) },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.card)
                .padding(bottom = Space.card).navigationBarsPadding().imePadding(),
            content = content,
        )
    }
}

/** Tappable sheet line. */
@Composable
fun SheetRow(title: String, sub: String = "", danger: Boolean = false, highlight: Boolean = false, onClick: () -> Unit) {
    val n = LocalN.current
    val tick = rememberTick()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.control)).clickable { tick(); onClick() }
            .padding(horizontal = Space.small, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (highlight) { SignalDot(true, Modifier.size(6.dp)); Spacer(Modifier.width(10.dp)) }
            Text(title, style = NType.body, color = if (danger) n.accent else n.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (sub.isNotEmpty()) Text(sub.uppercase(), style = NType.micro, color = n.muted, modifier = Modifier.padding(top = 3.dp, start = if (highlight) 16.dp else 0.dp))
    }
}

/**
 * A picker row that opens a sheet of options.
 *
 * The trailing-lambda parameter MUST be last: Kotlin binds `f(a, b) { }` to the final parameter, so putting
 * `sub` after `onPick` would silently make every call site's lambda a `String`.
 */
@Composable
fun ChoiceRow(title: String, options: List<String>, value: String, sub: String = "", onPick: (String) -> Unit) {
    val n = LocalN.current
    var open by remember { mutableStateOf(false) }
    NRow(title, sub, onClick = { open = true }, trailing = { Text(value.replaceFirstChar { it.uppercase() }, style = NType.label, color = n.dim, maxLines = 1) })
    if (open) NSheet({ open = false }) {
        NLabel(title, Modifier.padding(bottom = Space.small))
        options.forEach { o -> SheetRow(o.replaceFirstChar { it.uppercase() }, highlight = o == value) { onPick(o); open = false } }
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, sub: String = "", onChange: (Boolean) -> Unit) =
    NRow(title, sub, onClick = { onChange(!checked) }, trailing = { NSwitch(checked, onChange) })

/** Inline text setting; holds its own draft so typing never fights the store. */
@Composable
fun FieldRow(title: String, value: String, multiline: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    var t by remember(title) { mutableStateOf(value) }
    NField(title, t, { t = it; onChange(it) }, Modifier.padding(horizontal = Space.card), multiline = multiline, keyboard = keyboard)
}

@Composable
fun NumberRow(title: String, value: Int, onChange: (Int) -> Unit) =
    FieldRow(title, value.toString(), keyboard = KeyboardType.Number) { v -> v.toIntOrNull()?.let(onChange) }

/** Inline busy row: the technical signal plus a plain-language label. */
@Composable
fun BusyRow(label: String, modifier: Modifier = Modifier) {
    val n = LocalN.current
    Row(modifier.fillMaxWidth().padding(vertical = Space.small), verticalAlignment = Alignment.CenterVertically) {
        SignalLoader(true)
        Spacer(Modifier.width(Space.standard))
        Text(label, style = NType.body, color = n.text)
    }
}

/** Centred busy block for sheets. */
@Composable
fun BusyBlock(label: String, detail: String = "") {
    val n = LocalN.current
    Column(Modifier.fillMaxWidth().padding(vertical = Space.section), horizontalAlignment = Alignment.CenterHorizontally) {
        SignalLoader(true, Modifier.size(width = 140.dp, height = 12.dp), segments = 20)
        Spacer(Modifier.height(Space.standard))
        Text(label, style = NType.body, color = n.text)
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(Space.micro))
            NLabel(detail)
        }
    }
}

/**
 * Live speed figure. Plain monospace numerals at a large-ish size with the unit set smaller and dimmer,
 * so the number reads first. Numerals are intentionally NOT dot-matrix: the dot display is reserved for
 * instrument graphics, and ordinary figures should match the rest of the app's typography.
 */
@Composable
fun SpeedValue(value: String, modifier: Modifier = Modifier) {
    val n = LocalN.current
    val num = value.substringBeforeLast(' ', "")
    val unit = value.substringAfter(' ', "")
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(num, style = NType.mono.copy(fontSize = 22.sp), color = n.text, maxLines = 1)
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(2.dp))
            Text(unit, style = NType.micro, color = n.muted, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/** Compact label + value readout, used inside cards. */
@Composable
fun NStat(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = LocalN.current.text) {
    val n = LocalN.current
    Column(modifier) {
        NLabel(label)
        Spacer(Modifier.height(5.dp))
        Text(value.uppercase(), style = NType.mono, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A hairline slider for a continuous setting. Material's Slider brings its own track, thumb and ripple, all
 * of which would break the visual language, so this is drawn as a filled/empty hairline pair with a small
 * square thumb - the same inversion geometry as the chips and the nav bar's active marker.
 *
 * Implemented with a plain drag rather than `Modifier.draggable` so the value updates on release, not on
 * every pixel: a background dim dragged live re-decodes the wallpaper on each frame.
 */
@Composable
fun NSlider(
    label: String, value: Float, min: Float = 0f, max: Float = 1f, steps: Int = 0,
    valueLabel: String = "${(value * 100).toInt()}%",
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
) {
    val n = LocalN.current
    val tick = rememberTick()
    val span = (max - min).coerceAtLeast(0.0001f)
    val frac = ((value - min) / span).coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth().padding(horizontal = Space.card, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NLabel(label, Modifier.weight(1f))
            Text(valueLabel, style = NType.mono, color = n.dim)
        }
        Spacer(Modifier.height(10.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(24.dp)) {
            // Constraints give the track's real width in Dp, so the thumb offset stays in Dp the whole way.
            val trackW = maxWidth
            val thumb = 6.dp
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(Radius.control))
                    .pointerInput(min, max, steps, trackW) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            if (size.width <= 0) return@detectHorizontalDragGestures
                            val perPx = span / size.width
                            val raw = (value + dragAmount * perPx).coerceIn(min, max)
                            val snapped = if (steps > 0) {
                                val stepSize = span / (steps + 1)
                                min + Math.round((raw - min) / stepSize) * stepSize
                            } else raw
                            onChange(snapped.coerceIn(min, max))
                        }
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { tick() },
            ) {
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(2.dp).background(n.line))
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(frac).height(2.dp).background(n.text))
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = (trackW * frac - thumb / 2).coerceIn(0.dp, trackW - thumb))
                        .size(thumb)
                        .background(n.text, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

/** Subscription allowance: used / total plus time remaining. Returns null-ish (renders nothing) if unknown. */
@Composable
fun SubAllowance(name: String, up: Long, down: Long, total: Long, expire: Long, modifier: Modifier = Modifier) {
    val n = LocalN.current
    if (total <= 0 && expire <= 0) return
    val used = up + down
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NLabel(name, Modifier.weight(1f))
            if (total > 0) Text("${fmtShort(used)} / ${fmtShort(total)}", style = NType.mono, color = n.dim, fontSize = 12.sp)
        }
        if (total > 0) {
            Spacer(Modifier.height(8.dp))
            SegmentedBar(used.toFloat() / total)
        }
        if (expire > 0) {
            val days = (expire - System.currentTimeMillis() / 1000) / 86_400L
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (days < 3) SignalDot(true, Modifier.size(5.dp))
                if (days < 3) Spacer(Modifier.width(6.dp))
                NLabel(
                    when { days < 0 -> "Expired ${-days} days ago"; days == 0L -> "Expires today"; else -> "$days days left" },
                    color = if (days < 3) n.accent else n.muted,
                )
            }
        }
    }
}

fun fmtShort(b: Long): String {
    val u = arrayOf("B", "K", "M", "G", "T")
    var v = b.coerceAtLeast(0).toDouble(); var i = 0
    while (v >= 1024 && i < 4) { v /= 1024; i++ }
    return (if (i == 0) "%.0f" else "%.1f").format(v) + u[i]
}
