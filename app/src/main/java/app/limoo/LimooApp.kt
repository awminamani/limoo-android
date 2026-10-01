package app.limoo

import android.app.Application
import android.content.Context
import app.limoo.core.Latency
import app.limoo.format.LinkParser
import app.limoo.model.AppSettings
import app.limoo.model.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

class LimooApp : Application() {
    lateinit var store: Store
    override fun onCreate() { super.onCreate(); store = Store(this) }
}

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("limoo", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val servers = MutableStateFlow(runCatching { json.decodeFromString<List<Server>>(sp.getString("servers", "[]")!!) }.getOrDefault(emptyList()))
    val settings = MutableStateFlow(runCatching { json.decodeFromString<AppSettings>(sp.getString("settings", "{}")!!) }.getOrDefault(AppSettings()))
    val subs = MutableStateFlow(runCatching { json.decodeFromString<List<String>>(sp.getString("subs", "[]")!!) }.getOrDefault(emptyList()))
    val selectedId = MutableStateFlow(sp.getString("selected", null))

    fun selected(): Server? = servers.value.firstOrNull { it.id == selectedId.value } ?: servers.value.firstOrNull()
    fun select(id: String) { selectedId.value = id; persist() }
    fun addServers(l: List<Server>) { servers.value = (servers.value + l).distinctBy { listOf(it.protocol, it.host, it.port, it.uuid, it.path) }; persist() }
    fun remove(id: String) { servers.value = servers.value.filter { it.id != id }; persist() }
    fun upsert(s: Server) { servers.update { l -> if (l.any { it.id == s.id }) l.map { if (it.id == s.id) s else it } else l + s }; persist() }
    fun setPing(id: String, ms: Long) = servers.update { l -> l.map { if (it.id == id) it.copy(pingMs = ms) else it } }
    /** TCP-connect latency for every server (pingMs: -1 untested, 0 timeout, else ms). */
    suspend fun pingAll() = coroutineScope {
        val sem = Semaphore(8)
        servers.value.map { s -> async { sem.withPermit { setPing(s.id, Latency.tcp(s)) } } }.awaitAll()
    }
    suspend fun autoSelectBest() { pingAll(); servers.value.filter { it.pingMs > 0 }.minByOrNull { it.pingMs }?.let { select(it.id) } }
    fun update(f: (AppSettings) -> AppSettings) { settings.value = f(settings.value); persist() }

    suspend fun addSubscription(url: String) { if (url !in subs.value) subs.value = subs.value + url; persist(); refresh(url) }
    suspend fun refreshAll() = subs.value.forEach { runCatching { refresh(it) } }
    private suspend fun refresh(url: String) {
        val text = withContext(Dispatchers.IO) {
            (URL(url).openConnection() as HttpURLConnection).apply { setRequestProperty("User-Agent", "Limoo/0.1"); connectTimeout = 15000; readTimeout = 15000 }
                .inputStream.bufferedReader().readText()
        }
        addServers(LinkParser.parseMany(text).map { it.copy(group = url.substringAfter("://").substringBefore('/')) })
    }

    private fun persist() = sp.edit().putString("servers", json.encodeToString(servers.value)).putString("settings", json.encodeToString(settings.value))
        .putString("subs", json.encodeToString(subs.value)).putString("selected", selectedId.value).apply()
}
