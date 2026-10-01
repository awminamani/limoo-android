package app.limoo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.limoo.Store
import app.limoo.format.ImportPreview
import app.limoo.model.Server
import app.limoo.model.Subscription
import kotlinx.coroutines.launch

// ============================== add ==============================

@Composable
fun AddSheet(peek: ImportPreview?, onDismiss: () -> Unit, a: Actions, onSub: () -> Unit, onManual: () -> Unit) {
    val found = when {
        peek == null -> "NOTHING USEFUL COPIED"
        peek.needsPassword -> "ENCRYPTED .LIMOO FILE"
        peek.servers.isNotEmpty() -> "${peek.servers.size} DETECTED"
        peek.subUrls.isNotEmpty() -> "SUBSCRIPTION LINK"
        else -> "NOTHING USEFUL COPIED"
    }
    NSheet(onDismiss) {
        NLabel("ADD", Modifier.padding(bottom = 6.dp))
        SheetRow("Paste from clipboard", found, highlight = peek != null) { onDismiss(); a.pasteImport() }
        SheetRow("Scan QR code", "VLESS - VMESS - TROJAN - SS - LIMOO") { onDismiss(); a.scan() }
        SheetRow("Import file", ".LIMOO OR TEXT") { onDismiss(); a.pickFile() }
        SheetRow("Subscription link", "AUTO-UPDATING LIST") { onDismiss(); onSub() }
        SheetRow("Enter manually") { onDismiss(); onManual() }
    }
}

@Composable
fun SubFormSheet(initial: Subscription?, onDismiss: () -> Unit, onSave: (name: String, url: String, auto: Boolean) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }; var url by remember { mutableStateOf(initial?.url ?: "") }
    var auto by remember { mutableStateOf(initial?.autoUpdate ?: true) }
    NSheet(onDismiss) {
        NLabel(if (initial == null) "NEW SUBSCRIPTION" else "EDIT SUBSCRIPTION")
        NField("URL", url, { url = it.trim() }, placeholder = "https://")
        NField("NAME (OPTIONAL)", name, { name = it })
        if (initial != null) ToggleRow("Auto-update", auto) { auto = it }
        val ok = url.startsWith("http")
        if (initial == null && ok) NBusy("FETCHES ON SAVE", detail = "SUBSCRIPTION")
        NButton(if (initial == null) "ADD AND FETCH" else "SAVE", { onSave(name.trim(), url, auto); onDismiss() }, Modifier.fillMaxWidth().padding(top = 14.dp), primary = true, enabled = ok)
    }
}

fun ago(epochSec: Long): String {
    if (epochSec <= 0) return "NEVER"
    val m = (System.currentTimeMillis() / 1000 - epochSec) / 60
    return when { m < 1 -> "JUST NOW"; m < 60 -> "${m}M AGO"; m < 1440 -> "${m / 60}H AGO"; else -> "${m / 1440}D AGO" }
}

@Composable
fun SubsSheet(store: Store, onDismiss: () -> Unit) {
    val n = LocalN.current; val scope = rememberCoroutineScope()
    val subs by store.subs.collectAsState(); val servers by store.servers.collectAsState()
    var busy by remember { mutableStateOf(setOf<String>()) }
    var form by remember { mutableStateOf<Subscription?>(null) }; var adding by remember { mutableStateOf(false) }; var deleting by remember { mutableStateOf<Subscription?>(null) }
    fun refresh(s: Subscription) {
        scope.launch {
            busy = busy + s.id
            runCatching { store.refreshSub(s.id) }.onSuccess { Ui.say("${s.name}: $it SERVERS") }.onFailure { Ui.say("FAILED: ${it.message}") }
            busy = busy - s.id
        }
    }
    NSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NLabel("SUBSCRIPTIONS", Modifier.weight(1f))
            NButton("ADD", { adding = true }, compact = true)
        }
        Spacer(Modifier.height(10.dp))
        if (subs.isEmpty()) Text("No subscriptions yet. Add a link and Limoo keeps the list fresh.", style = NType.body, color = n.dim, modifier = Modifier.padding(vertical = 20.dp))
        LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(subs, key = { it.id }) { s ->
                NCard(Modifier.fillMaxWidth(), radius = 22.dp) {
                    Column(Modifier.padding(16.dp)) {
                        Text(s.name, style = NType.body, color = n.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        NLabel("${servers.count { it.subId == s.id }} SERVERS - ${ago(s.updatedAt)}", Modifier.padding(top = 3.dp))
                        if (s.total > 0) {
                            DotBar(((s.upload + s.download).toFloat() / s.total), Modifier.padding(top = 10.dp))
                            NLabel("${fmtBytes(s.upload + s.download)} / ${fmtBytes(s.total)}", Modifier.padding(top = 6.dp))
                        }
                        if (s.error.isNotEmpty()) NLabel(s.error, Modifier.padding(top = 6.dp), color = n.accent)
                        Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NButton(if (s.id in busy) "..." else "UPDATE", { refresh(s) }, enabled = s.id !in busy, compact = true)
                            NButton("EDIT", { form = s }, compact = true)
                            NButton("DELETE", { deleting = s }, danger = true, compact = true)
                        }
                    }
                }
            }
        }
    }
    if (adding) SubFormSheet(null, { adding = false }) { name, url, _ ->
        scope.launch { runCatching { store.addSubscription(url, name) }.onSuccess { Ui.say("ADDED $it SERVERS") }.onFailure { Ui.say("FAILED: ${it.message}") } }
    }
    form?.let { s ->
        SubFormSheet(s, { form = null }) { name, url, auto ->
            val changed = url != s.url
            store.updateSub(s.copy(name = name.ifBlank { Store.hostOf(url) }, url = url, autoUpdate = auto))
            if (changed) refresh(s.copy(url = url))
        }
    }
    deleting?.let { s ->
        NSheet({ deleting = null }) {
            NLabel("DELETE ${s.name}?")
            Text("Keep its servers, or remove them with it.", style = NType.body, color = n.dim, modifier = Modifier.padding(vertical = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NButton("KEEP SERVERS", { store.removeSub(s.id, true); deleting = null }, Modifier.weight(1f))
                NButton("REMOVE ALL", { store.removeSub(s.id, false); deleting = null }, Modifier.weight(1f), danger = true)
            }
        }
    }
}

// ============================== import preview ==============================

@Composable
fun ImportSheet(p: ImportPreview, existingKeys: Set<String>, a: Actions, onDismiss: () -> Unit, busy: Boolean = false) {
    val n = LocalN.current
    var pw by remember(p) { mutableStateOf("") }
    val dupes = remember(p) { p.servers.filter { Store.key(it) in existingKeys }.map { it.id }.toSet() }
    var chosen by remember(p) { mutableStateOf(p.servers.map { it.id }.toSet() - dupes) }
    var group by remember(p) { mutableStateOf(p.title) }; var restore by remember(p) { mutableStateOf(false) }
    var subName by remember(p) { mutableStateOf("") }
    val expired = p.expires > 0 && p.expires < System.currentTimeMillis() / 1000

    NSheet(onDismiss) {
        if (p.needsPassword) {
            NLabel("IMPORT - ${p.source}")
            Text("Password protected", style = NType.title, color = n.text, modifier = Modifier.padding(top = 8.dp))
            NField("PASSWORD", pw, { pw = it }, password = true)
            NButton("UNLOCK", { a.unlock(p, pw) }, Modifier.fillMaxWidth().padding(top = 14.dp), primary = true, enabled = pw.isNotEmpty())
        } else {
            NLabel("IMPORT - ${p.source}")
            Text(p.title.ifBlank { if (p.servers.isNotEmpty()) "${p.servers.size} server${if (p.servers.size == 1) "" else "s"}" else "Subscription" }, style = NType.title, color = n.text, modifier = Modifier.padding(top = 8.dp))
            if (p.note.isNotBlank()) Text(p.note, style = NType.body, color = n.dim, modifier = Modifier.padding(top = 4.dp))
            if (p.expires > 0) NLabel(if (expired) "THIS CONFIG HAS EXPIRED" else "VALID UNTIL ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(p.expires * 1000))}", Modifier.padding(top = 6.dp), color = if (expired) n.accent else n.dim)

            p.subUrls.forEach { u ->
                NLabel("SUBSCRIPTION", Modifier.padding(top = 14.dp))
                Text(u, style = NType.mono, color = n.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (p.subUrls.size == 1) NField("NAME (OPTIONAL)", subName, { subName = it })

            if (p.servers.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    NLabel("SERVERS ${chosen.size}/${p.servers.size}", Modifier.weight(1f))
                    NButton(if (chosen.size == p.servers.size) "NONE" else "ALL", { chosen = if (chosen.size == p.servers.size) emptySet() else p.servers.map { it.id }.toSet() }, compact = true)
                }
                LazyColumn(Modifier.heightIn(max = 260.dp).padding(top = 6.dp)) {
                    items(p.servers, key = { it.id }) { s: Server ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { chosen = if (s.id in chosen) chosen - s.id else chosen + s.id }.padding(vertical = 9.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            NCheck(s.id in chosen); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = NType.body, color = n.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                NLabel("${s.protocol} - ${s.network} - ${s.security}" + if (s.id in dupes) " - ALREADY ADDED" else "")
                            }
                        }
                    }
                }
                NField("GROUP (OPTIONAL)", group, { group = it }, placeholder = "keep servers organised")
            }
            if (p.settings != null) ToggleRow("Also restore settings", restore, "FROM BACKUP") { restore = it }

            val count = chosen.size + p.subUrls.size
            if (busy) NBusyBlock("IMPORTING", "${chosen.size} SERVERS - ${p.subUrls.size} SUBSCRIPTIONS")
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NButton("CANCEL", onDismiss, Modifier.weight(1f), enabled = !busy)
                NButton(if (busy) "..." else if (count > 0 || restore) "IMPORT $count" else "IMPORT",
                    { a.commitImport(p, p.servers.filter { it.id in chosen }, group.trim(), restore, subName.trim()) },
                    Modifier.weight(1f), primary = true, enabled = !busy && (count > 0 || restore))
            }
        }
    }
}

// ============================== share ==============================

@Composable
fun ShareSheet(servers: List<Server>, onDismiss: () -> Unit, a: Actions) {
    val n = LocalN.current
    var name by remember { mutableStateOf(if (servers.size == 1) servers[0].name else "Limoo servers") }
    var note by remember { mutableStateOf("") }; var pw by remember { mutableStateOf("") }; var days by remember { mutableStateOf(0) }
    NSheet(onDismiss) {
        NLabel("SHARE ${servers.size} SERVER${if (servers.size == 1) "" else "S"}")
        NField("TITLE", name, { name = it })
        NField("NOTE FOR THE RECEIVER (OPTIONAL)", note, { note = it })
        NField("PASSWORD (OPTIONAL)", pw, { pw = it }, password = true)
        NLabel("EXPIRES", Modifier.padding(top = 10.dp, bottom = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "NEVER", 1 to "1 DAY", 7 to "7 DAYS", 30 to "30 DAYS").forEach { (d, l) -> NChip(l, days == d) { days = d } }
        }
        Text(if (pw.isEmpty()) "Anyone with the file can read the servers." else "Encrypted with AES-256. Share the password separately.", style = NType.label, color = n.dim, modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NButton("LINK", { onDismiss(); a.share(servers, name, note, days, pw.ifEmpty { null }, true) }, Modifier.weight(1f))
            NButton("FILE", { onDismiss(); a.share(servers, name, note, days, pw.ifEmpty { null }, false) }, Modifier.weight(1f), primary = true)
        }
    }
}
