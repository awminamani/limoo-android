package app.limoo

import android.app.Application
import android.content.Context
import app.limoo.core.Latency
import app.limoo.format.LinkParser
import app.limoo.model.AppSettings
import app.limoo.model.SUB_FETCH_TIMEOUT_MS
import app.limoo.model.Server
import app.limoo.model.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

class LimooApp : Application() {
    lateinit var store: Store
    override fun onCreate() {
        super.onCreate()
        app.limoo.core.Crash.appContext = applicationContext
        // Record the fatal exception before the process dies: the file survives the crash and is shown
        // on the next launch, so a connect crash can be identified without logcat.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            runCatching { app.limoo.core.Crash.log("FATAL on ${thread.name}", err) }
            prev?.uncaughtException(thread, err)
        }
        store = Store(this)
        app.limoo.widget.WidgetSync.start(this)
        // Register (or re-register) the periodic subscription refresh against the stored interval.
        app.limoo.core.SubUpdateWorker.sync(applicationContext, store.settings.value)
    }
}

class Store(ctx: Context) {
    companion object {
        /** Identity of a server for de-duplication / merging. */
        fun key(s: Server) = "${s.protocol}|${s.host}|${s.port}|${s.uuid}|${s.path}|${s.sni}"
        fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?')
    }

    private val appContext = ctx.applicationContext
    private val sp = app.limoo.core.SecurePrefs.wrap(ctx.getSharedPreferences("limoo", Context.MODE_PRIVATE), setOf("servers", "subs"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val servers = MutableStateFlow(runCatching { json.decodeFromString<List<Server>>(sp.getString("servers", "[]")!!) }.getOrDefault(emptyList()))
    val settings = MutableStateFlow(runCatching { json.decodeFromString<AppSettings>(sp.getString("settings", "{}")!!) }.getOrDefault(AppSettings()))
    val subs = MutableStateFlow(loadSubs())
    val selectedId = MutableStateFlow(sp.getString("selected", null))
    val pinging = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Number of subscription fetches currently in flight, and a label for the overlay. The waiting
     * animation is driven from here rather than from a screen's local state, so it appears for EVERY
     * entry point (add sheet, import preview, subscriptions sheet, the periodic worker) instead of only
     * the one the user happened to start from.
     */
    val fetchingSubs = MutableStateFlow(0)
    val fetchingLabel = MutableStateFlow("")

    /** v0.1 stored subscriptions as a plain URL list; v0.2 stores objects. Accept both. */
    private fun loadSubs(): List<Subscription> {
        val raw = sp.getString("subs", "[]")!!
        return runCatching { json.decodeFromString<List<Subscription>>(raw) }
            .getOrElse { runCatching { json.decodeFromString<List<String>>(raw).map { Subscription(name = hostOf(it), url = it) } }.getOrDefault(emptyList()) }
    }

    // ---------- servers ----------
    fun selected(): Server? = servers.value.firstOrNull { it.id == selectedId.value } ?: servers.value.firstOrNull()
    /**
     * Selected server, and re-establish the tunnel if we were connected. Centralised here so EVERY path
     * that changes the selection (Home picker, Servers list, tile, widget) behaves the same: stop the core
     * and tun first, then come back up on the new server. Selecting from the Servers list used to leave the
     * old connection running with the new server name showing.
     */
    fun select(id: String) {
        if (id == selectedId.value) return
        selectedId.value = id; persist()
        if (app.limoo.core.LimooVpnService.state.value == app.limoo.core.LimooVpnService.State.Connected ||
            app.limoo.core.LimooVpnService.state.value == app.limoo.core.LimooVpnService.State.Connecting
        ) {
            app.limoo.core.LimooVpnService.reconnect()
        }
    }
    fun touch(id: String) { servers.update { l -> l.map { if (it.id == id) it.copy(lastUsed = System.currentTimeMillis()) else it } }; persist() }

    /** Adds servers that are not already present; returns the ones actually added. */
    fun addServers(l: List<Server>): List<Server> {
        val have = servers.value.map { key(it) }.toHashSet()
        val fresh = l.filter { have.add(key(it)) }
        if (fresh.isNotEmpty()) { servers.update { it + fresh }; persist() }
        return fresh
    }

    fun upsert(s: Server) { servers.update { l -> if (l.any { it.id == s.id }) l.map { if (it.id == s.id) s else it } else l + s }; persist() }
    fun duplicate(id: String) { servers.value.firstOrNull { it.id == id }?.let { s -> upsert(s.copy(id = java.util.UUID.randomUUID().toString(), name = s.name + " copy", fav = false, subId = "")) } }
    fun moveToGroup(ids: Set<String>, group: String) { servers.update { l -> l.map { if (it.id in ids) it.copy(group = group, subId = "") else it } }; persist() }

    /** Favorites toggle: if every given server is already a favorite they are un-favorited, otherwise all become favorites. */
    fun toggleFav(ids: Set<String>) {
        val all = servers.value.filter { it.id in ids }.all { it.fav }
        servers.update { l -> l.map { if (it.id in ids) it.copy(fav = !all) else it } }; persist()
    }

    /** Removes servers and returns (index, server) pairs so the removal can be undone in place. */
    fun removeMany(ids: Set<String>): List<Pair<Int, Server>> {
        val removed = servers.value.withIndex().filter { it.value.id in ids }.map { it.index to it.value }
        servers.update { l -> l.filter { it.id !in ids } }; persist(); return removed
    }

    fun restore(removed: List<Pair<Int, Server>>) {
        servers.update { l -> val m = l.toMutableList(); removed.sortedBy { it.first }.forEach { (i, s) -> m.add(i.coerceAtMost(m.size), s) }; m }; persist()
    }

    fun removeDuplicates(): Int {
        val seen = HashSet<String>(); var n = 0
        servers.update { l -> l.filter { if (seen.add(key(it))) true else { n++; false } } }; if (n > 0) persist(); return n
    }

    fun removeDead(): Int { val dead = servers.value.filter { it.pingMs == 0L }.map { it.id }.toSet(); if (dead.isNotEmpty()) removeMany(dead); return dead.size }

    /**
     * Latency test. ids == null tests everything. Real mode uses the core's own probe (slower, accurate).
     *
     * Results are written back in ONE pass rather than per server. Writing each result as it landed meant a
     * full list copy, a StateFlow emission and a recomposition of every visible row per server - ping 200
     * servers and the list rebuilt itself 200 times. A pinging row is shown by [pinging] instead, which
     * does not touch the server list at all.
     */
    suspend fun pingAll(ids: Collection<String>? = null, real: Boolean = false) {
        val targets = servers.value.filter { ids == null || it.id in ids }
        if (targets.isEmpty()) return
        pinging.update { it + targets.map { s -> s.id } }
        val st = settings.value
        try {
            val results = coroutineScope {
                // The real probe spins up a throwaway core per server, so keep concurrency low.
                val sem = Semaphore(if (real) 2 else 8)
                targets.map { s ->
                    async {
                        val ms = sem.withPermit { if (real) pingReal(s, st) else Latency.tcp(s, st.pingTimeoutMs) }
                        s.id to ms
                    }
                }.awaitAll()
            }
            val byId = results.toMap()
            servers.update { l -> l.map { byId[it.id]?.let { m -> it.copy(pingMs = m) } ?: it } }
            persist()
        } finally {
            pinging.update { it - targets.map { s -> s.id }.toSet() }
        }
    }

    /** Real delay, falling back to TCP when the core probe is unavailable (-1) or fails (0). */
    private suspend fun pingReal(s: Server, st: AppSettings): Long {
        val ms = Latency.real(appContext, s, st, st.testUrl)
        return if (ms > 0) ms else if (ms == 0L) 0L else Latency.tcp(s, st.pingTimeoutMs)
    }

    suspend fun autoSelectBest(real: Boolean = false) { pingAll(real = real); servers.value.filter { it.pingMs > 0 }.minByOrNull { it.pingMs }?.let { select(it.id) } }

    fun update(f: (AppSettings) -> AppSettings) {
        settings.value = f(settings.value); persist()
    }

    // ---------- subscriptions ----------
    private class Fetched(val servers: List<Server>, val title: String?, val upload: Long, val download: Long, val total: Long, val expire: Long)

    private suspend fun fetch(url: String): Fetched = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "Limoo/0.6"); connectTimeout = 15_000; readTimeout = 20_000
        }
        try {
            if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
            val list = LinkParser.parseMany(c.inputStream.bufferedReader().readText())
            if (list.isEmpty()) throw Exception("No servers found in subscription")
            val info = (c.getHeaderField("subscription-userinfo") ?: "").split(';').mapNotNull { p ->
                p.trim().split('=').takeIf { it.size == 2 }?.let { it[0].trim().lowercase() to (it[1].trim().toLongOrNull() ?: 0L) }
            }.toMap()
            val title = c.getHeaderField("profile-title")?.let { t ->
                if (t.startsWith("base64:")) runCatching { String(android.util.Base64.decode(t.removePrefix("base64:"), android.util.Base64.DEFAULT)) }.getOrNull() else t
            }?.takeIf { it.isNotBlank() }
            Fetched(list, title, info["upload"] ?: 0L, info["download"] ?: 0L, info["total"] ?: 0L, info["expire"] ?: 0L)
        } finally {
            c.disconnect()
        }
    }

    /** Adds the subscription and fetches it. The subscription is kept even if the first fetch fails (error is shown, retry works). */
    suspend fun addSubscription(url: String, name: String = ""): Int {
        val existing = subs.value.firstOrNull { it.url == url }
        val sub = existing ?: Subscription(name = name.ifBlank { hostOf(url) }, url = url)
        if (existing == null) { subs.update { it + sub }; persist() }
        return refreshSub(sub.id)
    }

    /**
     * Re-fetches one subscription, merging by server identity so selection, favorites and pings survive.
     *
     * Bounded by [SUB_FETCH_TIMEOUT_MS]: the waiting overlay can be dismissed at any time, and this is what
     * guarantees the request cannot outlive it. [fetchingSubs] is reference-counted so overlapping fetches
     * from an import cannot hide each other's overlay.
     */
    suspend fun refreshSub(id: String): Int {
        val sub = subs.value.firstOrNull { it.id == id } ?: return 0
        fetchingSubs.update { it + 1 }
        fetchingLabel.value = sub.name
        try {
            val r = withTimeoutOrNull(SUB_FETCH_TIMEOUT_MS) { fetch(sub.url) }
                ?: throw Exception("Timed out after ${SUB_FETCH_TIMEOUT_MS / 1000}s")
            val name = if (sub.name == hostOf(sub.url) && r.title != null) r.title else sub.name
            val old = servers.value.associateBy { key(it) }
            val merged = r.servers.map { n ->
                val o = old[key(n)]
                (if (o != null) n.copy(id = o.id, fav = o.fav, pingMs = o.pingMs, lastUsed = o.lastUsed) else n).copy(subId = id, group = name)
            }
            val keys = merged.map { key(it) }.toHashSet()
            servers.update { l -> l.filter { it.subId != id && key(it) !in keys } + merged }
            subs.update { l -> l.map { if (it.id == id) it.copy(name = name, updatedAt = System.currentTimeMillis() / 1000, upload = r.upload, download = r.download, total = r.total, expire = r.expire, error = "") else it } }
            persist(); return merged.size
        } catch (e: Exception) {
            subs.update { l -> l.map { if (it.id == id) it.copy(error = e.message ?: "Failed") else it } }; persist(); throw e
        } finally {
            fetchingSubs.update { (it - 1).coerceAtLeast(0) }
        }
    }

    /**
     * Refreshes every subscription that is due. Both switches must be on: the global
     * [AppSettings.subAutoUpdate] and the subscription's own `autoUpdate`. [force] ignores the clock.
     */
    suspend fun refreshAll(force: Boolean = false) {
        val st = settings.value
        if (!force && !st.subAutoUpdate) return
        val now = System.currentTimeMillis() / 1000
        val due = subs.value.filter { it.autoUpdate && (force || now - it.updatedAt > st.subUpdateIntervalMin * 60L) }
        due.forEach { runCatching { refreshSub(it.id) } }
    }

    fun updateSub(s: Subscription) {
        subs.update { l -> l.map { if (it.id == s.id) s else it } }
        servers.update { l -> l.map { if (it.subId == s.id) it.copy(group = s.name) else it } }; persist()
    }

    fun removeSub(id: String, keepServers: Boolean) {
        subs.update { l -> l.filter { it.id != id } }
        servers.update { l -> if (keepServers) l.map { if (it.subId == id) it.copy(subId = "") else it } else l.filter { it.subId != id } }; persist()
    }

    /**
     * Debounced persistence.
     *
     * Every mutation used to serialise the WHOLE server list to JSON and hand it to SharedPreferences on
     * the caller's thread. Typing one character into a settings field re-encrypted and rewrote every server
     * in the list; a single ping sweep wrote once per server. Now the write is coalesced and pushed onto
     * the IO dispatcher, with [flush] available for the paths that must not lose the change.
     */
    @Volatile private var persistJob: kotlinx.coroutines.Job? = null

    private fun persist() {
        persistJob?.cancel()
        persistJob = ioScope.launch {
            delay(350)                       // collapse a burst of edits into one write
            write()
        }
    }

    /** Writes immediately on the calling thread. Used when the process is about to go away. */
    fun flush() { persistJob?.cancel(); write() }

    private fun write() {
        sp.edit().putString("servers", json.encodeToString(servers.value)).putString("settings", json.encodeToString(settings.value))
            .putString("subs", json.encodeToString(subs.value)).putString("selected", selectedId.value).apply()
    }
}