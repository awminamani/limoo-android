package app.limoo.core

import android.content.Context
import app.limoo.model.AppSettings
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

interface CoreEngine {
    fun start(configJson: String, tunFd: Int, st: AppSettings) // tunFd = -1 in proxy-only mode
    fun stop()
    fun measureDelay(configJson: String, url: String): Long

    /**
     * Bytes moved by the running core SINCE THE PREVIOUS CALL (the core resets its counters on read),
     * or null when the core cannot report stats at all. A running core with no traffic returns (0, 0).
     */
    fun queryTraffic(): CoreTraffic? = null

    /** False once the core has died on its own (used by the watchdog / kill switch). */
    fun isAlive(): Boolean = true
}

/**
 * Traffic sample. In [CoreEngine.queryTraffic] [up]/[down] are deltas since the last read. In
 * [LimooVpnService.traffic] they are SESSION TOTALS and [upRate]/[downRate] are bytes per second.
 */
data class CoreTraffic(val up: Long, val down: Long, val upRate: Long = 0L, val downRate: Long = 0L)

/**
 * Talks to libv2ray.aar (2dust/AndroidLibXrayLite) through reflection, so the app compiles with or without the AAR and a
 * missing/incompatible AAR produces a readable error instead of a build failure. Expects the CoreController API
 * (Libv2ray.newCoreController + startLoop(config[, tunFd])), where Xray's own `tun` inbound reads the VPN fd.
 */
class LibXrayEngine(private val ctx: Context) : CoreEngine {
    @Volatile private var controller: Any? = null

    private fun lib(): Class<*> = try { Class.forName("libv2ray.Libv2ray") } catch (e: ClassNotFoundException) {
        throw IllegalStateException("Xray core (libv2ray.aar) is not bundled. Run ./gradlew fetchXrayCore (or copy it to app/libs) and rebuild.")
    }

    /** Invokes a core method, unwrapping InvocationTargetException. Background pollers must use [callSafe]. */
    private fun call(m: java.lang.reflect.Method, target: Any?, vararg a: Any?): Any? =
        try { m.invoke(target, *a) } catch (e: InvocationTargetException) { throw e.targetException }

    /** Never throws: Java-level failures from a background poller must not be fatal. */
    private fun callSafe(m: java.lang.reflect.Method, target: Any?, vararg a: Any?): Any? =
        try { call(m, target, *a) } catch (t: Throwable) { Crash.log("core call ${m.name}", t); null }

    /** True only when the controller exists and the core reports itself as running. */
    private fun isRunning(c: Any): Boolean {
        val g = c.javaClass.methods.firstOrNull { it.name == "getIsRunning" && it.parameterCount == 0 } ?: return true
        return (callSafe(g, c) as? Boolean) == true
    }

    /** Initialises the core environment (asset paths + xudp key) without starting a loop. Safe to call repeatedly. */
    fun ensureEnv(ctx: Context) {
        val l = lib()
        (l.methods.firstOrNull { it.name == "initCoreEnv" } ?: l.methods.firstOrNull { it.name == "initV2Env" })
            ?.let { call(it, null, GeoManager.dir(ctx).absolutePath, xudpBaseKey(ctx)) }
    }

    @Synchronized
    override fun start(configJson: String, tunFd: Int, st: AppSettings) {
        stop()
        val l = lib()
        // xray.xudp.basekey must be 32 bytes as unpadded base64url (43 chars). See README "Fixes".
        val key = xudpBaseKey(ctx)
        (l.methods.firstOrNull { it.name == "initCoreEnv" } ?: l.methods.firstOrNull { it.name == "initV2Env" })
            ?.let { call(it, null, GeoManager.dir(ctx).absolutePath, key) }
        val factory = l.methods.firstOrNull { it.name == "newCoreController" }
            ?: throw IllegalStateException("This libv2ray.aar is too old (no CoreController API). Use the latest AndroidLibXrayLite release.")
        val cbType = factory.parameterTypes[0]
        val cb = Proxy.newProxyInstance(cbType.classLoader, arrayOf(cbType)) { _, m, _ -> if (m.returnType == java.lang.Long.TYPE) 0L else null }
        val c = call(factory, null, cb)!!
        val loops = c.javaClass.methods.filter { it.name == "startLoop" }
        val two = loops.firstOrNull { it.parameterCount == 2 }
        if (two != null) call(two, c, configJson, tunFd) else call(loops.first(), c, configJson)
        controller = c
    }

    /** Stable per-install XUDP base key: 32 random bytes as unpadded base64url (43 chars). Generated once. */
    private fun xudpBaseKey(ctx: Context): String {
        val sp = ctx.getSharedPreferences("limoo_core", Context.MODE_PRIVATE)
        sp.getString("xudpBaseKey", null)?.let { if (it.length == 43) return it }
        val b = ByteArray(32)
        java.security.SecureRandom().nextBytes(b)
        val key = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        sp.edit().putString("xudpBaseKey", key).apply()
        return key
    }

    override fun isAlive(): Boolean = controller?.let { isRunning(it) } ?: false

    @Synchronized
    override fun stop() {
        val c = controller ?: return; controller = null
        c.javaClass.methods.firstOrNull { it.name == "stopLoop" }?.let { runCatching { call(it, c) } }
    }

    override fun measureDelay(configJson: String, url: String): Long = runCatching {
        val m = lib().methods.first { it.name == "measureOutboundDelay" }
        (call(m, null, configJson, url) as Number).toLong()
    }.getOrDefault(-1L)

    /**
     * CoreController.queryAllOutboundTrafficStats() returns PLAIN TEXT, not JSON:
     *   `proxy,uplink,1234;proxy,downlink,56789;direct,downlink,42;`
     * and it RESETS every counter it reports (counter.Set(0)), skipping counters that are zero. So:
     *  - the values are deltas since the previous call, never cumulative;
     *  - an empty string from a running core means "no traffic since last call", not "unavailable".
     * Only one caller may poll this (LimooVpnService), otherwise the two readers steal each other's bytes.
     */
    override fun queryTraffic(): CoreTraffic? {
        val c = controller ?: return null
        val m = c.javaClass.methods.firstOrNull { it.name == "queryAllOutboundTrafficStats" && it.parameterCount == 0 }
        if (m != null) {
            if (!isRunning(c)) return null
            val raw = callSafe(m, c) as? String ?: return null
            return CoreStats.parse(raw)
        }
        // Older AARs: queryStats(tag, direction) - also reset-on-read.
        val q = c.javaClass.methods.firstOrNull { it.name == "queryStats" && it.parameterCount == 2 } ?: return null
        if (!isRunning(c)) return null
        var up = 0L; var down = 0L
        for (tag in CoreStats.COUNTED_TAGS) {
            up += (callSafe(q, c, tag, "uplink") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0L
            down += (callSafe(q, c, tag, "downlink") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0L
        }
        return CoreTraffic(up, down)
    }
}

/**
 * Xray's own delay probe: Libv2ray.measureOutboundDelay(configJson, url) builds a temporary core with a
 * single outbound and reports the real round-trip in ms. -1 = failed or unavailable.
 */
object CoreDelay {
    @Volatile private var envReady = false

    fun measure(ctx: Context, configJson: String, url: String): Long = try {
        if (!envReady) { LibXrayEngine(ctx).ensureEnv(ctx); envReady = true }
        val l = Class.forName("libv2ray.Libv2ray")
        val m = l.methods.first { it.name == "measureOutboundDelay" }
        (m.invoke(null, configJson, url) as Number).toLong()
    } catch (e: Throwable) { -1L }
}

/**
 * Parser for the core's traffic text. Primary format is `tag,direction,value;` (AndroidLibXrayLite).
 * A JSON-ish `"uplink": n` form is still accepted in case a future core changes shape.
 *
 * Only real egress is counted: `proxy` and `direct`. `fragment` is a dialerProxy UNDER `proxy`, so counting
 * it too would double every byte of TLS traffic when fragment is on; `block` is a blackhole.
 */
object CoreStats {
    val COUNTED_TAGS = listOf("proxy", "direct")
    private val json = Regex("\"?(uplink|downlink)\"?\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE)

    fun parse(raw: String): CoreTraffic {
        val text = raw.trim()
        var up = 0L; var down = 0L
        if (text.isEmpty()) return CoreTraffic(0, 0)
        // Shape must be decided by the leading '{', not by the presence of a comma: JSON separators and
        // quoted keys contain commas too, so contains(',') misroutes JSON into the CSV branch and every
        // count is lost. (The JSON-fallback unit test caught exactly this.)
        val isJson = text.startsWith("{")
        if (!isJson) {
            text.split(';').forEach { rec ->
                val p = rec.trim().split(',')
                if (p.size != 3) return@forEach
                val tag = p[0].trim(); val dir = p[1].trim().lowercase(); val v = p[2].trim().toLongOrNull() ?: return@forEach
                if (tag !in COUNTED_TAGS || v <= 0) return@forEach
                when (dir) { "uplink" -> up += v; "downlink" -> down += v }
            }
        } else {
            json.findAll(text).forEach { m ->
                val v = m.groupValues[2].toLongOrNull() ?: return@forEach
                if (m.groupValues[1].lowercase() == "uplink") up += v else down += v
            }
        }
        return CoreTraffic(up, down)
    }
}
