package app.limoo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.limoo.Store
import app.limoo.model.SUB_FETCH_TIMEOUT_MS
import kotlinx.coroutines.delay

/**
 * The waiting animation for a subscription import.
 *
 * The rule this exists to enforce: **the animation is shown while the fetch is running, and never before
 * it.** An earlier version put a "Fetches on save" hint inside the form, so the loader appeared the moment
 * the user tapped ADD, before a single byte had been requested - and it then stayed up behind the sheet for
 * the whole fetch. Here the overlay's visibility is derived from [Store.fetchingSubs], which the store
 * increments immediately before the request and decrements in a `finally`, so it can only be up while a
 * request is genuinely outstanding.
 *
 * **Tapping anywhere dismisses it, and only the animation.** The fetch is a coroutine owned by the store,
 * not by this composable, so dismissing the overlay cannot cancel it; the request finishes (or fails) and
 * the usual snackbar reports the result. [SUB_FETCH_TIMEOUT_MS] bounds it either way, so a server that never
 * answers cannot leave a request running behind a dismissed overlay.
 *
 * The dismissal is per-fetch, not permanent: it resets whenever the fetch count changes, so the next
 * subscription shows its own loader again.
 */
@Composable
fun SubFetchOverlay(store: Store) {
    val n = LocalN.current
    val fetching by store.fetchingSubs.collectAsState()
    val label by store.fetchingLabel.collectAsState()

    // Dismissed for THIS fetch. Keyed on the count so a new fetch brings the animation back.
    var dismissedFor by remember { mutableStateOf(-1) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }

    LaunchedEffect(fetching) {
        if (fetching > 0) {
            startedAt = System.currentTimeMillis()
            elapsed = 0L
            // The count dropping to 0 ends the fetch; allow the next one to show its loader again.
            if (dismissedFor == fetching) dismissedFor = -1
        }
    }

    // A one-second tick so the "still waiting" readout advances. It stops as soon as the overlay is hidden,
    // because this effect is keyed on visibility through the AnimatedVisibility below.
    val visible = fetching > 0 && dismissedFor != fetching
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            elapsed = System.currentTimeMillis() - startedAt
            delay(1000)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(n.bg.copy(alpha = 0.92f))
                // Tap anywhere to hide. indication = null and no ripple: this is a scrim, not a control, and
                // a ripple under a full-screen loader reads as a bug.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { dismissedFor = fetching },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Space.card),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NLabel("Fetching subscription")
                Spacer(Modifier.height(Space.standard))
                // The signal loader is the app's loading language, so the wait is drawn as instrumentation
                // rather than a spinner.
                SignalLoader(true, Modifier.width(180.dp).height(12.dp), segments = 24)
                Spacer(Modifier.height(Space.standard))
                Text(
                    if (label.isBlank()) "Waiting for configs" else "Waiting for ${label}",
                    style = NType.title, color = n.text, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Space.small))
                Text(
                    secondsText(elapsed),
                    style = NType.mono, color = n.dim, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Space.hero))
                Text(
                    "Tap anywhere to hide. The import keeps running.",
                    style = NType.bodySmall, color = n.muted, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** "0s" while it just started, then seconds, and a note once the request is close to its limit. */
private fun secondsText(elapsedMs: Long): String {
    val s = elapsedMs / 1000
    val limit = SUB_FETCH_TIMEOUT_MS / 1000
    return when {
        s >= limit -> "${s}s · giving up at ${limit}s"
        s < 60 -> "${s}s"
        else -> "${s / 60}m ${s % 60}s"
    }
}

/**
 * The same indicator, inline, for a sheet that wants to show progress without covering the screen.
 * Deliberately NOT used as a pre-submit hint - see [SubFetchOverlay].
 */
@Composable
fun SubFetchInline(store: Store, modifier: Modifier = Modifier) {
    val n = LocalN.current
    val fetching by store.fetchingSubs.collectAsState()
    if (fetching == 0) return
    Row(modifier.fillMaxWidth().padding(vertical = Space.small), verticalAlignment = Alignment.CenterVertically) {
        SignalLoader(true)
        Spacer(Modifier.width(Space.standard))
        Text("Fetching ${fetching} subscription${if (fetching == 1) "" else "s"}", style = NType.body, color = n.text)
    }
}