package app.limoo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.limoo.Store
import app.limoo.core.LimooVpnService.State
import app.limoo.format.ImportPreview
import app.limoo.model.Server
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest

/** Fire-and-forget snackbar messages from anywhere (activity, view code). */
object Ui {
    class Msg(val text: String, val action: String?, val onAction: (() -> Unit)?)
    val msgs = MutableSharedFlow<Msg>(extraBufferCapacity = 16)
    fun say(text: String, action: String? = null, onAction: (() -> Unit)? = null) { msgs.tryEmit(Msg(text, action, onAction)) }
}

/** Everything the screens need from the activity. */
data class Actions(
    val toggle: () -> Unit, val reconnect: () -> Unit,
    val pasteImport: () -> Unit, val pickFile: () -> Unit, val scan: () -> Unit, val peekClip: () -> ImportPreview?,
    val share: (servers: List<Server>, name: String, note: String, expiresDays: Int, password: String?, asLink: Boolean, withLook: Boolean, withGroup: Boolean) -> Unit,
    /** Copies the plain vless://vmess://trojan://ss:// links for these servers to the clipboard. */
    val copyStandardLinks: (List<Server>) -> Unit,
    val exportBackup: () -> Unit,
    val commitImport: (preview: ImportPreview, chosen: List<Server>, group: String, restoreSettings: Boolean, subName: String) -> Unit,
    val unlock: (preview: ImportPreview, password: String) -> Unit,
    val addSub: (url: String, name: String) -> Unit,
    /** Opens the system photo picker for a custom app background. No storage permission required. */
    val pickBackground: () -> Unit = {},
    /** Called with true/false as long work (import, subscription fetch) starts and finishes. */
    val setBusy: (Boolean) -> Unit = {},
)

@Composable
fun LimooRoot(
    store: Store, state: State, error: String?, preview: ImportPreview?, clipOffer: ImportPreview?, aIn: Actions,
    onDismissPreview: () -> Unit, onDismissClip: () -> Unit, onOpenPreview: (ImportPreview) -> Unit,
) {
    var busy by remember { mutableStateOf<String?>(null) }
    // Counted, not boolean: an import can start several subscriptions at once, and the first one to finish
    // must not hide the spinner while the rest are still running.
    val busyCount = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val a = remember(aIn) {
        aIn.copy(setBusy = { on ->
            busyCount.intValue = (busyCount.intValue + if (on) 1 else -1).coerceAtLeast(0)
            busy = if (busyCount.intValue > 0) "WORKING" else null
        })
    }
    val n = LocalN.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var addOpen by remember { mutableStateOf(false) }; var subForm by remember { mutableStateOf(false) }; var manual by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val servers by store.servers.collectAsState()
    // Read as Compose state, not .value: the background layer depends on it, so a change to the picked
    // image or the accent has to recompose this scope.
    val st by store.settings.collectAsState()
    val existingKeys = remember(servers) { servers.map { Store.key(it) }.toSet() }
    val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    LaunchedEffect(Unit) {
        Ui.msgs.collectLatest { m ->
            if (snackbar.showSnackbar(m.text.uppercase(), m.action, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) m.onAction?.invoke()
        }
    }

    // One root Box for everything. Every tab gets the accent-reactive wallpaper behind it; the scrim
    // rises on the list-heavy screens (Servers, Settings) where many hairline rows sit over the artwork,
    // while Home keeps it light because it has the most negative space. Cards supply their own surface
    // fill, so text on top stays legible either way.
    //
    // The accent comes from LocalN, which NTheme recomputes from the stored AppSettings.accent, so a
    // change to the setting retints the wallpaper immediately: one source of truth, no restart.
    Box(Modifier.fillMaxSize()) {
        WallpaperLayers(st, scrim = if (tab == 0) 0.10f else 0.45f)
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            AnimatedVisibility(clipOffer != null) {
                clipOffer?.let { o ->
                    ClipBanner(o, onImport = {
                        if (o.needsPassword || o.subUrls.isNotEmpty()) onOpenPreview(o) else a.commitImport(o, o.servers, o.title, false, "")
                    }, onDismiss = onDismissClip)
                }
            }
            AnimatedVisibility(busy != null) {
                busy?.let { label ->
                    BusyRow(label, Modifier.padding(horizontal = Space.card, vertical = Space.compact))
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> HomeScreen(store, state, error, a, onAdd = { addOpen = true }, busy = busy)
                    1 -> ServersScreen(store, a, onAdd = { addOpen = true }, busy = busy)
                    else -> SettingsScreen(store, a)
                }
            }
            if (!imeUp) NavBar(tab) { tab = it }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp, start = 24.dp, end = 24.dp)) { d ->
            Snackbar(d, shape = CircleShape, containerColor = n.text, contentColor = n.onText, actionColor = n.accent)
        }
        // Drawn above everything, and only while a request is actually in flight. Tap-to-hide dismisses the
        // animation without touching the job. See SubFetchOverlay for why it is driven from the store.
        SubFetchOverlay(store)
    }

    if (addOpen) {
        val peek = remember { a.peekClip() }
        AddSheet(peek, { addOpen = false }, a, onSub = { subForm = true }, onManual = { manual = true })
    }
    if (subForm) SubFormSheet(null, { subForm = false }) { name, url, _ -> a.addSub(url, name) }
    if (manual) ServerEditor(null, { store.upsert(it); manual = false; Ui.say("SERVER ADDED") }, { manual = false })
    preview?.let { ImportSheet(it, existingKeys, a, onDismissPreview, busy != null) }
}

/**
 * Navigation as a hairline-ruled bar, not a floating pill. The spec calls out giant floating nav pills as an
 * anti-pattern on mobile: navigation should read as part of the information system. A top hairline, three
 * left-aligned labels, and inversion for the active tab.
 */
@Composable
private fun NavBar(tab: Int, onTab: (Int) -> Unit) {
    val n = LocalN.current
    val tick = rememberTick()
    Column(Modifier.fillMaxWidth().background(n.bg).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(n.line))
        // Each tab is centred in its own equal third, so the labels sit on a regular rhythm and the gaps
        // between them are identical. Left-aligning them (as an earlier version did) bunched them toward
        // the start and left a wide empty gap at the end.
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.card, vertical = Space.compact)) {
            listOf("Home", "Servers", "Settings").forEachIndexed { i, l ->
                val sel = tab == i
                Box(
                    Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { tick(); onTab(i) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Active state is a filled square, the same inversion language as the chips.
                        Box(
                            Modifier.size(6.dp).background(
                                if (sel) n.text else n.line,
                                RoundedCornerShape(1.dp),
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            l,
                            style = NType.label,
                            color = if (sel) n.text else n.muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipBanner(o: ImportPreview, onImport: () -> Unit, onDismiss: () -> Unit) {
    val n = LocalN.current
    NCard(Modifier.padding(horizontal = Space.card, vertical = Space.compact).fillMaxWidth()) {
        Row(Modifier.padding(start = Space.standard, end = Space.compact, top = Space.small, bottom = Space.small), verticalAlignment = Alignment.CenterVertically) {
            SignalDot(true, Modifier.size(5.dp))
            Spacer(Modifier.width(Space.small))
            Column(Modifier.weight(1f)) {
                NLabel("In clipboard")
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        o.needsPassword -> "Encrypted .limoo file"
                        o.servers.size == 1 -> o.servers[0].name
                        else -> "${o.servers.size} servers"
                    },
                    style = NType.body, color = n.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            NButton(if (o.needsPassword || o.subUrls.isNotEmpty()) "Open" else "Import", onImport, primary = true, compact = true)
            TextButton(onDismiss) { Text("Dismiss", style = NType.micro, color = n.muted) }
        }
    }
}
