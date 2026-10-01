package app.limoo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
class Actions(
    val toggle: () -> Unit, val reconnect: () -> Unit,
    val pasteImport: () -> Unit, val pickFile: () -> Unit, val scan: () -> Unit, val peekClip: () -> ImportPreview?,
    val share: (servers: List<Server>, name: String, note: String, expiresDays: Int, password: String?, asLink: Boolean) -> Unit,
    val exportBackup: () -> Unit,
    val commitImport: (preview: ImportPreview, chosen: List<Server>, group: String, restoreSettings: Boolean, subName: String) -> Unit,
    val unlock: (preview: ImportPreview, password: String) -> Unit,
    val addSub: (url: String, name: String) -> Unit,
)

@Composable
fun LimooRoot(
    store: Store, state: State, error: String?, preview: ImportPreview?, clipOffer: ImportPreview?, a: Actions,
    onDismissPreview: () -> Unit, onDismissClip: () -> Unit, onOpenPreview: (ImportPreview) -> Unit,
) {
    val n = LocalN.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var addOpen by remember { mutableStateOf(false) }; var subForm by remember { mutableStateOf(false) }; var manual by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val servers by store.servers.collectAsState()
    val existingKeys = remember(servers) { servers.map { Store.key(it) }.toSet() }
    val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    LaunchedEffect(Unit) {
        Ui.msgs.collectLatest { m ->
            if (snackbar.showSnackbar(m.text.uppercase(), m.action, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) m.onAction?.invoke()
        }
    }

    Box(Modifier.fillMaxSize().background(n.bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            AnimatedVisibility(clipOffer != null) {
                clipOffer?.let { o ->
                    ClipBanner(o, onImport = {
                        if (o.needsPassword || o.subUrls.isNotEmpty()) onOpenPreview(o) else a.commitImport(o, o.servers, o.title, false, "")
                    }, onDismiss = onDismissClip)
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> HomeScreen(store, state, error, a, onAdd = { addOpen = true })
                    1 -> ServersScreen(store, a, onAdd = { addOpen = true })
                    else -> SettingsScreen(store, a)
                }
            }
            if (!imeUp) NavPill(tab) { tab = it }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp, start = 24.dp, end = 24.dp)) { d ->
            Snackbar(d, shape = CircleShape, containerColor = n.text, contentColor = n.onText, actionColor = n.accent)
        }
    }

    if (addOpen) {
        val peek = remember { a.peekClip() }
        AddSheet(peek, { addOpen = false }, a, onSub = { subForm = true }, onManual = { manual = true })
    }
    if (subForm) SubFormSheet(null, { subForm = false }) { name, url, _ -> a.addSub(url, name) }
    if (manual) ServerEditor(null, { store.upsert(it); manual = false; Ui.say("SERVER ADDED") }, { manual = false })
    preview?.let { ImportSheet(it, existingKeys, a, onDismissPreview) }
}

@Composable
private fun NavPill(tab: Int, onTab: (Int) -> Unit) {
    val n = LocalN.current; val tick = rememberTick()
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.clip(CircleShape).background(n.surface).border(1.dp, n.line, CircleShape).padding(4.dp)) {
            listOf("HOME", "SERVERS", "SETTINGS").forEachIndexed { i, l ->
                val sel = tab == i
                Box(
                    Modifier.clip(CircleShape).background(if (sel) n.text else Color.Transparent).clickable { tick(); onTab(i) }.padding(horizontal = 18.dp, vertical = 11.dp),
                ) { Text(l, style = NType.label, color = if (sel) n.onText else n.dim) }
            }
        }
    }
}

@Composable
private fun ClipBanner(o: ImportPreview, onImport: () -> Unit, onDismiss: () -> Unit) {
    val n = LocalN.current
    NCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(), radius = 24.dp) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(n.accent)); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                NLabel("IN CLIPBOARD")
                Text(
                    when { o.needsPassword -> "Encrypted .limoo file"; o.servers.size == 1 -> o.servers[0].name; else -> "${o.servers.size} servers" },
                    style = NType.body, color = n.text, maxLines = 1,
                )
            }
            NButton(if (o.needsPassword || o.subUrls.isNotEmpty()) "OPEN" else "IMPORT", onImport, primary = true, compact = true)
            IconButton(onDismiss) { Icon(Icons.Default.Close, "Dismiss", tint = n.dim) }
        }
    }
}
