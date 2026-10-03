package app.limoo.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.limoo.Store
import app.limoo.core.Latency
import app.limoo.format.LinkBuilder
import app.limoo.model.Server
import kotlinx.coroutines.launch

@Composable
private fun ServerRow(
    s: Server, active: Boolean, picked: Boolean, selecting: Boolean, testing: Boolean, privacy: Boolean,
    onClick: () -> Unit, onLong: () -> Unit, onMenu: () -> Unit,
) {
    val n = LocalN.current
    // No swipe. The row is a plain tappable line: tap selects, long-press enters multi-select, and the
    // overflow mark opens the same actions the swipe used to carry (share, delete, edit, duplicate).
    NCard(Modifier.fillMaxWidth(), onClick = onClick, onLongClick = onLong, highlight = picked) {
        Row(Modifier.padding(start = Space.standard, end = Space.micro, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) NCheck(picked) else NRadio(active)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = NType.body, color = n.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${s.protocol} - ${s.network} - ${s.security}".uppercase() + "  " + mask("${s.host}:${s.port}", privacy),
                    style = NType.label, color = n.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                DotMeter(s.pingMs, testing)
                Text(
                    when { testing -> "..."; s.pingMs > 0 -> "${s.pingMs} ms"; s.pingMs == 0L -> "Timeout"; else -> "" },
                    style = NType.micro,
                    color = if (s.pingMs == 0L) n.accent else n.muted, modifier = Modifier.padding(top = 5.dp),
                )
            }
            if (!selecting) MenuMark(onMenu) else Spacer(Modifier.width(Space.small))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServersScreen(store: Store, a: Actions, onAdd: () -> Unit, busy: String? = null) {
    val n = LocalN.current; val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val servers by store.servers.collectAsState(); val selId by store.selectedId.collectAsState(); val st by store.settings.collectAsState()
    val subs by store.subs.collectAsState()
    val pinging by store.pinging.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }; var filter by rememberSaveable { mutableStateOf("ALL") }
    var picked by remember { mutableStateOf(setOf<String>()) }; var collapsed by remember { mutableStateOf(setOf<String>()) }
    var rowMenu by remember { mutableStateOf<Server?>(null) }; var moreOpen by remember { mutableStateOf(false) }; var sortOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Server?>(null) }; var qr by remember { mutableStateOf<Server?>(null) }
    var subsOpen by remember { mutableStateOf(false) }; var shareList by remember { mutableStateOf<List<Server>?>(null) }
    var groupFor by remember { mutableStateOf<Set<String>?>(null) }; var confirmWipe by remember { mutableStateOf(false) }
    val selecting = picked.isNotEmpty()
    BackHandler(selecting) { picked = emptySet() }

    val groups = remember(servers) { servers.map { it.group }.filter { it.isNotEmpty() }.distinct() }
    val visible = remember(servers, query, filter, st.sortBy) {
        servers.filter { s -> (filter == "ALL" || (filter == "FAV" && s.fav) || s.group == filter) &&
            (query.isBlank() || listOf(s.name, s.host, s.protocol, s.network, s.group).any { it.contains(query, true) }) }
            .let { l ->
                when (st.sortBy) {
                    "ping" -> l.sortedBy { if (it.pingMs > 0) it.pingMs else Long.MAX_VALUE }
                    "name" -> l.sortedBy { it.name.lowercase() }
                    "recent" -> l.sortedByDescending { it.lastUsed }
                    else -> l.sortedByDescending { it.fav }
                }
            }
    }
    val grouped = filter == "ALL" && query.isBlank() && st.sortBy == "manual" && groups.isNotEmpty()
    val sections = remember(visible, grouped) { if (grouped) visible.groupBy { it.group }.toList() else listOf("" to visible) }
    val activeId = selId ?: servers.firstOrNull()?.id

    fun deleteWithUndo(ids: Set<String>) { val removed = store.removeMany(ids); Ui.say("DELETED ${removed.size}", "UNDO") { store.restore(removed) } }
    fun copyLink(s: Server) { ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", LinkBuilder.build(s))); Ui.say("LINK COPIED") }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selecting) {
                    Text("${picked.size} selected", style = NType.title, color = n.text); Spacer(Modifier.weight(1f))
                    NButton("All", { picked = visible.map { it.id }.toSet() }, compact = true); Spacer(Modifier.width(Space.compact))
                    NButton("Done", { picked = emptySet() }, primary = true, compact = true)
                } else {
                    Text("Servers", style = NType.display, color = n.text)
                    Spacer(Modifier.width(Space.compact))
                    NLabel("${servers.size}")
                    Spacer(Modifier.weight(1f))
                    NButton(if (st.realPing) "Ping" else "TCP", { scope.launch { store.pingAll(visible.map { it.id }, real = st.realPing) } }, compact = true); Spacer(Modifier.width(Space.compact))
                    NButton("Tools", { moreOpen = true }, compact = true)
                }
            }
            // Subscription allowances (data used / total, days left) stay pinned here instead of only inside
            // group headers, where they disappeared as soon as the user searched or changed the sort.
            if (subs.any { it.total > 0 || it.expire > 0 }) {
                NCard(Modifier.fillMaxWidth().padding(top = Space.compact)) {
                    Column(Modifier.padding(Space.standard)) {
                        NLabel("Subscription")
                        subs.filter { it.total > 0 || it.expire > 0 }.forEachIndexed { i, s ->
                            if (i > 0) NDivider()
                            SubAllowance(
                                "${s.name.uppercase()}  -  ${servers.count { it.subId == s.id }} SERVERS",
                                s.upload, s.download, s.total, s.expire,
                                Modifier.padding(top = if (i > 0) 12.dp else 10.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            NSearch(query, { query = it })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NChip("All ${servers.size}", filter == "ALL") { filter = "ALL" }
                if (servers.any { it.fav }) NChip("Favorites", filter == "FAV") { filter = "FAV" }
                groups.forEach { g -> NChip(g, filter == g) { filter = if (filter == g) "ALL" else g } }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 150.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (visible.isEmpty()) item("empty") {
                    Column(Modifier.fillMaxWidth().padding(vertical = Space.hero), horizontalAlignment = Alignment.CenterHorizontally) {
                        DotField(Modifier.size(width = 120.dp, height = 68.dp))
                        Text(if (servers.isEmpty()) "Nothing here yet." else "Try another search or filter.", style = NType.body, color = n.dim, modifier = Modifier.padding(top = Space.standard, bottom = Space.standard))
                        if (servers.isEmpty()) NButton("Add servers", onAdd, primary = true)
                    }
                }
                sections.forEach { (g, list) ->
                    if (grouped) item("h:$g") {
                        val sub = store.subs.value.firstOrNull { it.name == g }
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.control)).clickable { collapsed = if (g in collapsed) collapsed - g else collapsed + g }.padding(top = 12.dp, bottom = 4.dp, start = 6.dp, end = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NLabel(g.ifEmpty { "My servers" }, Modifier.weight(1f))
                                NLabel("${list.size}")
                            }
                            // Data left / days left for this subscription, when the provider reports it.
                            if (sub != null && (sub.total > 0 || sub.expire > 0)) {
                                val used = sub.upload + sub.download
                                val days = (sub.expire - System.currentTimeMillis() / 1000) / 86_400L
                                Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (sub.total > 0) {
                                        SegmentedBar(used.toFloat() / sub.total, Modifier.weight(1f).padding(end = Space.small), segments = 14, height = 5.dp)
                                        NLabel("${fmtShort(used)}/${fmtShort(sub.total)}")
                                    } else Spacer(Modifier.weight(1f))
                                    if (sub.expire > 0) NLabel(
                                        when { days < 0 -> "EXPIRED"; days == 0L -> "ENDS TODAY"; else -> "${days}D LEFT" },
                                        Modifier.padding(start = if (sub.total > 0) 10.dp else 0.dp),
                                        color = if (days < 3) n.accent else n.dim,
                                    )
                                }
                            }
                        }
                    }
                    if (!grouped || g !in collapsed) items(list, key = { it.id }) { s ->
                        ServerRow(
                            s, active = s.id == activeId, picked = s.id in picked, selecting = selecting, testing = s.id in pinging, privacy = st.privacyMode,
                            onClick = { if (selecting) picked = if (s.id in picked) picked - s.id else picked + s.id else store.select(s.id) },
                            onLong = { picked = if (s.id in picked) picked - s.id else picked + s.id },
                            onMenu = { rowMenu = s },
                        )
                    }
                }
            }
        }

        if (selecting) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp, start = 20.dp, end = 20.dp).clip(CircleShape).background(n.surface).border(1.dp, n.line, CircleShape)
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NButton("Favorite", { store.toggleFav(picked) }, compact = true)
                NButton("Ping", { val ids = picked; scope.launch { store.pingAll(ids, real = st.realPing) } }, compact = true)
                NButton("Share", { shareList = servers.filter { it.id in picked } }, compact = true)
                NButton("Group", { groupFor = picked }, compact = true)
                NButton("DELETE", { deleteWithUndo(picked); picked = emptySet() }, danger = true, compact = true)
            }
        } else {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 16.dp).size(60.dp).clip(CircleShape).background(n.text).clickable { onAdd() },
                contentAlignment = Alignment.Center,
            ) { Text("+", color = n.onText, style = NType.title.copy(fontSize = 30.sp)) }
        }
    }

    rowMenu?.let { s ->
        NSheet({ rowMenu = null }) {
            NLabel(s.name, Modifier.padding(bottom = 6.dp))
            SheetRow("Edit") { rowMenu = null; editing = s }
            SheetRow("Duplicate") { rowMenu = null; store.duplicate(s.id) }
            SheetRow(if (s.fav) "Remove favorite" else "Add to favorites") { rowMenu = null; store.toggleFav(setOf(s.id)) }
            SheetRow("QR code") { rowMenu = null; qr = s }
            SheetRow("Copy link") { rowMenu = null; copyLink(s) }
            SheetRow("Share as .limoo") { rowMenu = null; shareList = listOf(s) }
            SheetRow(if (st.realPing) "Test real delay" else "Test TCP latency") { rowMenu = null; scope.launch { store.pingAll(listOf(s.id), real = st.realPing) } }
            SheetRow("Move to group") { rowMenu = null; groupFor = setOf(s.id) }
            SheetRow("Delete", danger = true) { rowMenu = null; deleteWithUndo(setOf(s.id)) }
        }
    }

    if (moreOpen) NSheet({ moreOpen = false }) {
        NLabel("Tools")
        Spacer(Modifier.height(Space.compact))
        SheetRow("Select best server", if (st.realPing) "REAL DELAY - SLOWER" else "TCP LATENCY") { moreOpen = false; scope.launch { store.autoSelectBest(real = st.realPing); Ui.say("BEST SERVER SELECTED") } }
        SheetRow("Sort", st.sortBy) { moreOpen = false; sortOpen = true }
        // No local "refreshing" flag: store.refreshAll increments the store's own fetch counter, which is what
        // drives the waiting overlay. A screen-local spinner would appear before the request and sit under the
        // sheet, which is exactly the behaviour SubFetchOverlay replaced.
        SheetRow("Update subscriptions") { moreOpen = false; scope.launch { store.refreshAll(force = true); Ui.say("SUBSCRIPTIONS UPDATED") } }
        SheetRow("Manage subscriptions") { moreOpen = false; subsOpen = true }
        SheetRow("Select all") { moreOpen = false; picked = servers.map { it.id }.toSet() }
        SheetRow("Remove duplicates") { moreOpen = false; val c = store.removeDuplicates(); Ui.say(if (c == 0) "NO DUPLICATES" else "REMOVED $c") }
        SheetRow("Remove unreachable", "timed out in last ping") { moreOpen = false; val c = store.removeDead(); Ui.say(if (c == 0) "NOTHING TO REMOVE" else "REMOVED $c") }
        SheetRow("Delete all servers", danger = true) { moreOpen = false; confirmWipe = true }
    }

    if (sortOpen) NSheet({ sortOpen = false }) {
        NLabel("Sort by")
        Spacer(Modifier.height(Space.compact))
        listOf("manual" to "MANUAL (FAVORITES FIRST)", "ping" to "LATENCY", "name" to "NAME", "recent" to "RECENTLY USED").forEach { (k, l) ->
            SheetRow(l, highlight = st.sortBy == k) { store.update { it.copy(sortBy = k) }; sortOpen = false }
        }
    }

    groupFor?.let { ids ->
        var g by remember { mutableStateOf("") }
        NSheet({ groupFor = null }) {
            NLabel("Move ${ids.size} to group")
            NField("NEW GROUP NAME", g, { g = it }, placeholder = "e.g. Work")
            if (groups.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { groups.forEach { x -> NChip(x, g == x) { g = x } } }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NButton("No group", { store.moveToGroup(ids, ""); groupFor = null; picked = emptySet() }, Modifier.weight(1f))
                NButton("Move", { store.moveToGroup(ids, g.trim()); groupFor = null; picked = emptySet() }, Modifier.weight(1f), primary = true, enabled = g.isNotBlank())
            }
        }
    }

    if (confirmWipe) NSheet({ confirmWipe = false }) {
        NLabel("Delete all ${servers.size} servers?")
        Text("You can undo right after.", style = NType.body, color = n.dim, modifier = Modifier.padding(vertical = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NButton("Cancel", { confirmWipe = false }, Modifier.weight(1f))
            NButton("Delete all", { confirmWipe = false; deleteWithUndo(servers.map { it.id }.toSet()) }, Modifier.weight(1f), danger = true)
        }
    }

    editing?.let { e -> ServerEditor(e, { store.upsert(it); editing = null }, { editing = null }) }
    qr?.let { QrDialog(it) { qr = null } }
    shareList?.let { l -> ShareSheet(l, { shareList = null; picked = emptySet() }, a) }
    if (subsOpen) SubsSheet(store, { subsOpen = false })
}
