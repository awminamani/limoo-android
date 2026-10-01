package app.limoo.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A server row with a swipe-to-reveal action, in the style of a Samsung One UI 8.5 notification.
 *
 * Behaviour, in order:
 *  - The row tracks the finger 1:1 at a single speed. No damping and no rubber-banding: damping during a
 *    drag desyncs the row from the finger, which is what reads as jank. The travel cap below is the only
 *    limit, and it is applied by clamping rather than by fighting the touch.
 *  - Past **75%** of the row width the row **completes its travel** and the action button is revealed at
 *    full size. Past that point the row no longer needs to follow the finger, so the remaining drag is
 *    ignored and the button is simply there to be tapped.
 *  - Releasing does **not** run the action. The button appears and is tapped, which removes the
 *    accidental-delete class of problem entirely. Tapping anywhere else on the row closes it again.
 *  - Animation runs only on release; during a drag the row is driven straight from the drag value.
 *
 * Right swipe = Share, left swipe = Delete. Delete still goes through the existing undo flow.
 */
private enum class SwipeDir { LEFT, RIGHT, NONE }

/** Fraction of the row width at which the row completes its travel and the button is shown. */
private const val ARM_THRESHOLD = 0.75f

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

    val density = LocalDensity.current
    var rowWidthPx by remember { mutableStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    var dir by remember { mutableStateOf(SwipeDir.NONE) }
    var open by remember { mutableStateOf(false) }   // the action button is showing

    // Gesture maths stays in pixels (translationX is px); layout widths need Dp, so convert once here.
    val travelPx = rowWidthPx * ARM_THRESHOLD
    val travelDp = with(density) { travelPx.toDp() }

    val shown by animateFloatAsState(
        targetValue = offset,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "swipeOffset",
    )

    fun close() {
        offset = 0f
        dir = SwipeDir.NONE
        open = false
    }

    fun openFor(d: SwipeDir) {
        dir = d
        open = true
        offset = sign(if (d == SwipeDir.RIGHT) 1f else -1f) * travelPx
        tick()
    }

    Box(modifier.fillMaxWidth().onSizeChanged { rowWidthPx = it.width }) {
        // Action layer, behind the row. When the row is open the button fills the revealed area and is
        // tappable; while dragging it is a plain, inert hint.
        if (dir != SwipeDir.NONE) {
            val isShare = dir == SwipeDir.RIGHT
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = if (isShare) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width((if (open) travelDp else travelDp * 0.9f).coerceAtLeast(1.dp))
                        .clip(RoundedCornerShape(Radius.card))
                        .background(if (isShare) n.surface2 else n.accent)
                        // Only interactive once fully open, so a drag can never fire the action.
                        .then(
                            if (open) {
                                Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    if (isShare) onShare() else onDelete()
                                    close()
                                }
                            } else Modifier,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (isShare) "Share" else "Delete",
                        style = NType.micro,
                        color = if (isShare) n.text else n.onText,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.padding(horizontal = Space.small),
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = shown
                    // A whisper of scale so the row lifts off the action behind it.
                    val p = if (travelPx > 0f) (abs(shown) / travelPx).coerceIn(0f, 1f) else 0f
                    scaleX = 1f - p * 0.02f
                }
                .then(
                    if (!enabled || rowWidthPx == 0) Modifier
                    else Modifier.pointerInput(rowWidthPx, open) {
                        detectHorizontalDragGestures(
                            onDragStart = { if (!open) { offset = 0f; dir = SwipeDir.NONE } },
                            onDragEnd = {
                                // Past the threshold the button stays out; otherwise spring home.
                                if (abs(offset) >= travelPx && dir != SwipeDir.NONE) openFor(dir)
                                else close()
                            },
                            onDragCancel = { close() },
                            onHorizontalDrag = { _, delta ->
                                if (open) return@detectHorizontalDragGestures   // already latched open
                                val next = (offset + delta).coerceIn(-travelPx, travelPx)
                                offset = next
                                dir = when {
                                    abs(next) < 4f -> SwipeDir.NONE
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
