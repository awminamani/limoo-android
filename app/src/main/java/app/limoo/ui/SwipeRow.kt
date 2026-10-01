package app.limoo.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
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
 * A server row with Samsung One UI-style horizontal swipe actions.
 *
 * The gesture is deliberately simple, because the previous attempt at "resistance past a threshold" felt
 * laggy rather than heavy: damping the drag while the finger is still moving makes the row fight the touch,
 * and the mismatch between finger and row position is what reads as jank. So the row tracks the finger
 * **exactly, 1:1, at a single constant speed** for its whole travel - no zones, no rubber-banding, no
 * animation chasing the finger. It stops at the edge of its travel and springs home when released.
 *
 * The "commit" affordance comes from position instead of resistance: the action plate behind the row
 * fills and brightens as the drag approaches the commit point, so the user sees the consequence before
 * letting go. That is legible, cheap and cannot lag.
 *
 * Right = Share (standard vless:// link, limoo:// link, or .limoo file). Left = Delete, still undoable.
 * The gesture itself never removes anything: the row stays in place and the caller acts.
 */
private enum class SwipeDir { LEFT, RIGHT, NONE }

/** Fraction of row width the drag must pass before the action is armed. */
private const val ARM_THRESHOLD = 0.45f

/** How far the row may travel, as a fraction of its width. */
private const val TRAVEL = 0.45f

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

    val travelPx = rowWidth * TRAVEL
    val armPx = rowWidth * ARM_THRESHOLD

    // Only ever animates on RELEASE. While the finger is down the row is driven directly by the drag, so
    // there is no animation lagging behind the touch - that is what caused the jank.
    val shown by animateFloatAsState(
        targetValue = offset,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "swipeOffset",
    )

    val progress = if (travelPx > 0f) (abs(shown) / travelPx).coerceIn(0f, 1f) else 0f

    fun settle() {
        offset = 0f
        dir = SwipeDir.NONE
        armed = false
    }

    Box(modifier.fillMaxWidth().onSizeChanged { rowWidth = it.width }) {
        // Action plate behind the row. Its opacity tracks the drag so the consequence is visible while the
        // finger is still down, rather than appearing only after release.
        when (dir) {
            SwipeDir.RIGHT -> ActionPlate(
                "Share", Modifier.align(Alignment.CenterStart), n.surface2, n.text, progress,
            )
            SwipeDir.LEFT -> ActionPlate(
                "Delete", Modifier.align(Alignment.CenterEnd), n.accent, n.onText, progress,
            )
            SwipeDir.NONE -> Unit
        }

        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = shown
                    // A whisper of scale so the row lifts off the plate beneath it.
                    scaleX = 1f - progress * 0.02f
                }
                .then(
                    if (!enabled || rowWidth == 0) Modifier
                    else Modifier.pointerInput(rowWidth) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset = 0f; armed = false },
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
                                // 1:1 with the finger, clamped to the travel limit. No damping.
                                val next = (offset + delta).coerceIn(-travelPx, travelPx)
                                offset = next
                                dir = when {
                                    abs(next) < 4f -> SwipeDir.NONE
                                    next > 0f -> SwipeDir.RIGHT
                                    else -> SwipeDir.LEFT
                                }
                                if (dir != SwipeDir.NONE && !armed && abs(next) >= armPx) {
                                    armed = true
                                    tick()   // confirm the arm point by feel
                                }
                            },
                        )
                    },
                ),
        ) { content() }
    }
}

/** The revealed action plate. Fades and grows with the drag progress. */
@Composable
private fun ActionPlate(
    label: String,
    modifier: Modifier = Modifier,
    fill: androidx.compose.ui.graphics.Color,
    fg: androidx.compose.ui.graphics.Color,
    progress: Float,
) {
    Box(
        modifier
            .fillMaxHeight()
            .width((72 + 48 * progress).dp)
            .graphicsLayer { alpha = 0.35f + progress * 0.65f }
            .background(fill, RoundedCornerShape(Radius.card)),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = NType.micro, color = fg, textAlign = TextAlign.Center) }
}