package app.limoo.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class Usage(val up: Long = 0, val down: Long = 0) {
    val total get() = up + down
    operator fun plus(o: Usage) = Usage(up + o.up, down + o.down)
}

/**
 * Persistent data-usage history: bytes per local day (kept 90 days) and per server (lifetime).
 * Written by LimooVpnService every ~30 s and at the end of a session; read by the Home usage card.
 */
object UsageLog {
    private const val KEEP_DAYS = 90L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    /** Bumped on every write so Compose readers refresh. */
    val version = MutableStateFlow(0)

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("limoo_usage", Context.MODE_PRIVATE)
    private fun load(ctx: Context, key: String): Map<String, Usage> =
        runCatching { json.decodeFromString<Map<String, Usage>>(sp(ctx).getString(key, "{}")!!) }.getOrDefault(emptyMap())

    fun dayKey(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

    fun days(ctx: Context) = load(ctx, "days")
    fun servers(ctx: Context) = load(ctx, "servers")

    @Synchronized
    fun add(ctx: Context, serverId: String, up: Long, down: Long, now: Long = System.currentTimeMillis()) {
        if (up <= 0 && down <= 0) return
        val d = Usage(up.coerceAtLeast(0), down.coerceAtLeast(0))
        val today = dayKey(now)
        val cutoff = LocalDate.parse(today).minusDays(KEEP_DAYS)
        val days = merge(days(ctx), today, d).filterKeys { k -> runCatching { !LocalDate.parse(k).isBefore(cutoff) }.getOrDefault(false) }
        val servers = if (serverId.isEmpty()) servers(ctx) else merge(servers(ctx), serverId, d)
        sp(ctx).edit().putString("days", json.encodeToString(days)).putString("servers", json.encodeToString(servers)).apply()
        version.value++
    }

    fun reset(ctx: Context) { sp(ctx).edit().clear().apply(); version.value++ }

    // ---- pure helpers (unit-tested) ----
    fun merge(m: Map<String, Usage>, key: String, d: Usage): Map<String, Usage> = m + (key to ((m[key] ?: Usage()) + d))

    /** The last [n] days ending at [today], oldest first, zero-filled. */
    fun lastN(days: Map<String, Usage>, today: LocalDate, n: Int): List<Pair<LocalDate, Usage>> =
        (n - 1 downTo 0).map { i -> today.minusDays(i.toLong()).let { it to (days[it.toString()] ?: Usage()) } }

    fun window(days: Map<String, Usage>, today: LocalDate, n: Int): Usage =
        lastN(days, today, n).fold(Usage()) { a, p -> a + p.second }
}
