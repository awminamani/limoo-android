package app.limoo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A row with Samsung One UI-style horizontal swipe actions.
 *
 * The stock `SwipeToDismissBox` flicks the row away the moment a threshold is crossed, which is too easy
 * to trigger by accident and leaves no confirmation. This behaves the way One UI 8.5 notifications do:
 *
 *  - The row follows the finger 1:1 up to a threshold (60% of its width).
 *  - Past that point the drag **resists** - movement is damped hard, so the row feels heavy and the user
 *    has to commit deliberately rather than by accident.
 *  - Crossing the threshold arms the action and fires haptic feedback, so release is confirmed by feel.
 *  - Letting go early springs back; letting go armed runs the action and the row settles with a short snap.
 *
 * Right swipe reveals Share, which offers the standard link (vless://, vmess://, ...) or a .limoo file.
 * Left swipe reveals Delete, which still routes through the existing undo flow.
 *
 * The gesture never removes anything by itself - the caller decides - so a mis-swipe is recoverable.
 */
private enum class SwipeDir { LEFT, RIGHT, NONE }

/** Fraction of the row width the drag must reach before the action arms. */
private const val ARM_THRESHOLD = 0.60f

/** Movement multiplier past the threshold: this is what produces the "heavy" feel. */
private const val RESISTANCE = 0.15f

private fun sign(v: Float): Float = if (v < 0f) -1f else 1f

@Composable
fun SwipeActionRow(
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val n = LocalN.current
    val tick = rememberTick(strong = true)

    var rowWidth by remember { mutableStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    var dir by remember { mutableStateOf(SwipeDir.NONE) }
    var armed by remember { mutableStateOf(false) }

    val armPx = (rowWidth * ARM_THRESHOLD).takeIf { rowWidth > 0 } ?: 160.dp.value * 0.6f

    // The row snaps back into place after release instead of stopping dead.
    val shown by animateFloatAsState(
        targetValue = offset,
        animationSpec = tween(durationMillis = 180),
        label = "swipeOffset",
    )

    fun settle() {
        offset = 0f
        dir = SwipeDir.NONE
        armed = false
    }

    Box(modifier.fillMaxWidth().onSizeChanged { rowWidth = it.width }) {
        // Action layer underneath, revealed as the row slides away. Delete uses the signal colour, which
        // is exactly the destructive case the accent is reserved for.
        Box(Modifier.matchParentSize()) {
            when (dir) {
                SwipeDir.RIGHT -> ActionPlate("Share", Modifier.align(Alignment.CenterStart), n.surface2, n.text)
                SwipeDir.LEFT -> ActionPlate("Delete", Modifier.align(Alignment.CenterEnd), n.accent.copy(alpha = 0.18f), n.accent)
                SwipeDir.NONE -> Unit
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = shown
                    // A hair of scale-down so the row reads as lifting off the layer beneath it.
                    val frac = (abs(shown) / rowWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
                    scaleX = 1f - frac * 0.03f
                }
                .then(
                    if (!enabled || rowWidth == 0) Modifier
                    else Modifier.pointerInput(rowWidth) {
                        var last = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { last = 0f },
                            onDragEnd = {
                                if (armed) {
                                    tick()
                                    when (dir) {
                                        SwipeDir.RIGHT -> onShare()
                                        SwipeDir.LEFT -> onDelete()
                                        SwipeDir.NONE -> Unit
                                    }
                                }
                                settle()
                            },
                            onDragCancel = { settle() },
                            onHorizontalDrag = { _, delta ->
                                val raw = last + delta
                                last = raw
                                val w = rowWidth.toFloat()
                                val proposed = raw.coerceIn(-w, w)
                                // 1:1 up to the threshold, then heavily damped.
                                val next =
                                    if (abs(proposed) <= armPx) proposed
                                    else sign(proposed) * (armPx + (abs(proposed) - armPx) * RESISTANCE)
                                if (!armed && abs(next) >= armPx) {
                                    armed = true
                                    tick()
                                }
                                offset = next
                                dir = when {
                                    abs(next) < 6f -> SwipeDir.NONE
                                    next > 0f -> SwipeDir.RIGHT
                                    else -> SwipeDir.LEFT
                                }
                            },
                        )
                    },
                ),
        ) { content() }
    }
}

/** The revealed action plate behind the row. */
@Composable
private fun ActionPlate(
    label: String,
    modifier: Modifier = Modifier,
    fill: androidx.compose.ui.graphics.Color,
    fg: androidx.compose.ui.graphics.Color,
) {
    Box(
        modifier
            .fillMaxHeight()
            .width(112.dp)
            .background(fill, RoundedCornerShape(Radius.card)),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = NType.micro, color = fg, textAlign = TextAlign.Center) }
}
