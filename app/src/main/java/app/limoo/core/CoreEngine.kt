package app.limoo.core

import android.content.Context
import app.limoo.model.AppSettings
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
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
 *
 * Every reflective lookup is resolved ONCE and cached. `Class.forName` and `Class.getMethods` are both
 * expensive - getMethods allocates a fresh copy of the whole method table on every call - and the traffic
 * poller runs them several times a second for the life of the tunnel. Re-resolving them per tick was pure
 * garbage-collector pressure on the connection path.
 */
class LibXrayEngine(private val ctx: Context) : CoreEngine {
    @Volatile private var controller: Any? = null

    /** Cached `libv2ray.Libv2ray` class and the statics we call on it. */
    private object LibCache {
        @Volatile var cls: Class<*>? = null
        @Volatile var initEnv: Method? = null
        @Volatile var newController: Method? = null
        @Volatile var measureDelay: Method? = null
    }

    /** Cached per-controller instance methods, keyed by the controller we are currently running. */
    @Volatile private var ctlFor: Any? = null
    @Volatile private var mStart2: Method? = null
    @Volatile private var mStart1: Method? = null
    @Volatile private var mStop: Method? = null
    @Volatile private var mRunning: Method? = null
    @Volatile private var mAllStats: Method? = null
    @Volatile private var mStats2: Method? = null

    private fun lib(): Class<*> = LibCache.cls ?: try {
        Class.forName("libv2ray.Libv2ray").also { LibCache.cls = it }
    } catch (e: ClassNotFoundException) {
        throw IllegalStateException("Xray core (libv2ray.aar) is not bundled. Run ./gradlew fetchXrayCore (or copy it to app/libs) and rebuild.")
    }

    /** Resolves and caches the statics. [fresh] forces a re-read, used only after an AAR is swapped. */
    private fun statics(): Unit {
        if (LibCache.newController != null) return
        val l = lib()
        LibCache.initEnv = l.methods.firstOrNull { it.name == "initCoreEnv" } ?: l.methods.firstOrNull { it.name == "initV2Env" }
        LibCache.newController = l.methods.firstOrNull { it.name == "newCoreController" }
        LibCache.measureDelay = l.methods.firstOrNull { it.name == "measureOutboundDelay" }
    }

    /** Invokes a core method, unwrapping InvocationTargetException. Background pollers must use [callSafe]. */
    private fun call(m: Method, target: Any?, vararg a: Any?): Any? =
        try { m.invoke(target, *a) } catch (e: InvocationTargetException) { throw e.targetException }

    /** Never throws: Java-level failures from a background poller must not be fatal. */
    private fun callSafe(m: Method, target: Any?, vararg a: Any?): Any? =
        try { call(m, target, *a) } catch (t: Throwable) { Crash.log("core call ${m.name}", t); null }

    /** True only when the controller exists and the core reports itself as running. */
    private fun isRunning(c: Any): Boolean {
        // No getIsRunning in this AAR: assume running rather than declaring the core dead.
        val m = mRunning ?: return true
        return (callSafe(m, c) as? Boolean) == true
    }

    /** Caches this controller's method table. Called once per start, never in the poll loop. */
    private fun bind(c: Any) {
        if (ctlFor === c) return
        val ms = c.javaClass.methods
        mStart2 = ms.firstOrNull { it.name == "startLoop" && it.parameterCount == 2 }
        mStart1 = ms.firstOrNull { it.name == "startLoop" && it.parameterCount == 1 }
        mStop = ms.firstOrNull { it.name == "stopLoop" }
        mRunning = ms.firstOrNull { it.name == "getIsRunning" && it.parameterCount == 0 }
        mAllStats = ms.firstOrNull { it.name == "queryAllOutboundTrafficStats" && it.parameterCount == 0 }
        mStats2 = ms.firstOrNull { it.name == "queryStats" && it.parameterCount == 2 }
        ctlFor = c
    }

    /** Initialises the core environment (asset paths + xudp key) without starting a loop. Safe to call repeatedly. */
    fun ensureEnv(ctx: Context) {
        statics()
        LibCache.initEnv?.let { call(it, null, GeoManager.dir(ctx).absolutePath, xudpBaseKey(ctx)) }
    }

    @Synchronized
    override fun start(configJson: String, tunFd: Int, st: AppSettings) {
        stop()
        statics()
        val l = lib()
        // xray.xudp.basekey must be 32 bytes as unpadded base64url (43 chars). See README "Fixes".
        LibCache.initEnv?.let { call(it, null, GeoManager.dir(ctx).absolutePath, xudpBaseKey(ctx)) }
        val factory = LibCache.newController
            ?: throw IllegalStateException("This libv2ray.aar is too old (no CoreController API). Use the latest AndroidLibXrayLite release.")
        val cbType = factory.parameterTypes[0]
        val cb = Proxy.newProxyInstance(cbType.classLoader, arrayOf(cbType)) { _, m, _ -> if (m.returnType == java.lang.Long.TYPE) 0L else null }
        val c = call(factory, null, cb)!!
        bind(c)
        val two = mStart2
        if (two != null) call(two, c, configJson, tunFd) else call(mStart1 ?: throw IllegalStateException("libv2ray has no startLoop"), c, configJson)
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

    override fun isAlive(): Boolean = controller?.let { bind(it); isRunning(it) } ?: false

    @Synchronized
    override fun stop() {
        val c = controller ?: return; controller = null
        // Drop the cached bindings with the controller: they belong to this instance's class, and a later
        // start may hand back a different implementation whose methods would not match.
        ctlFor = null
        mStop?.let { runCatching { call(it, c) } }
    }

    override fun measureDelay(configJson: String, url: String): Long = runCatching {
        statics()
        val m = LibCache.measureDelay ?: return -1L
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
        bind(c)
        if (!isRunning(c)) return null
        val all = mAllStats
        if (all != null) {
            val raw = callSafe(all, c) as? String ?: return null
            return CoreStats.parse(raw)
        }
        // Older AARs: queryStats(tag, direction) - also reset-on-read.
        val q = mStats2 ?: return null
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

    /**
     * One engine for the whole process, bound to the first context we are handed. The reflection lookups it
     * performs are cached inside the engine, so reusing it means pinging 200 servers no longer rescans the
     * AAR's method table 200 times.
     */
    @Volatile private var shared: LibXrayEngine? = null

    private fun engineFor(ctx: Context): LibXrayEngine {
        shared?.let { return it }
        return synchronized(this) { shared ?: LibXrayEngine(ctx.applicationContext).also { shared = it } }
    }

    fun measure(ctx: Context, configJson: String, url: String): Long = try {
        val e = engineFor(ctx)
        if (!envReady) { e.ensureEnv(ctx); envReady = true }
        e.measureDelay(configJson, url)
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