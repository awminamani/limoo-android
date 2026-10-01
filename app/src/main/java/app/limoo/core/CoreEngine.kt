package app.limoo.core

import android.content.Context
import app.limoo.model.AppSettings
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

interface CoreEngine {
    fun start(configJson: String, tunFd: Int, st: AppSettings)   // tunFd = -1 in proxy-only mode
    fun stop()
    fun measureDelay(configJson: String, url: String): Long

    /** Exact byte counters from the running core, or null when unavailable. */
    fun queryTraffic(): CoreTraffic? = null
}

/** Bytes counted by the Xray core itself (not Android's per-app estimates). */
data class CoreTraffic(val up: Long, val down: Long)

/**
 * Talks to libv2ray.aar (2dust/AndroidLibXrayLite) through reflection, so the app compiles with or without the AAR and a
 * missing/incompatible AAR produces a readable error instead of a build failure. Expects the CoreController API
 * (Libv2ray.newCoreController + startLoop(config[, tunFd])), where Xray's own `tun` inbound reads the VPN fd.
 */
class LibXrayEngine(private val ctx: Context) : CoreEngine {
    private var controller: Any? = null

    private fun lib(): Class<*> = try { Class.forName("libv2ray.Libv2ray") } catch (e: ClassNotFoundException) {
        throw IllegalStateException("Xray core (libv2ray.aar) is not bundled. Run ./gradlew fetchXrayCore (or copy it to app/libs) and rebuild.")
    }

    private fun call(m: java.lang.reflect.Method, target: Any?, vararg a: Any?): Any? =
        try { m.invoke(target, *a) } catch (e: InvocationTargetException) { throw e.targetException }

    /**
     * Initialises the core environment (asset paths + xudp key) without starting a loop. The delay probe
     * needs this to have run, otherwise it fails on geo lookups - which is why real ping used to report
     * nothing until a VPN connection had already been established once. Safe to call repeatedly.
     */
    fun ensureEnv(ctx: Context) {
        val l = lib()
        (l.methods.firstOrNull { it.name == "initCoreEnv" } ?: l.methods.firstOrNull { it.name == "initV2Env" })
            ?.let { call(it, null, GeoManager.dir(ctx).absolutePath, xudpBaseKey(ctx)) }
    }

    override fun start(configJson: String, tunFd: Int, st: AppSettings) {
        stop()
        val l = lib()
        // Xray reads the second initCoreEnv argument as xray.xudp.basekey and runs it through
        // base64.RawURLEncoding.DecodeString, requiring exactly 32 decoded bytes (43 base64url chars, unpadded).
        // Hex or padded base64 both fail with "xray.xudp.basekey: invalid value (BaseKey must be 32 bytes)".
        // ANDROID_ID is neither, so use a stable random 32-byte key encoded as unpadded base64url.
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

    override fun stop() {
        val c = controller ?: return; controller = null
        c.javaClass.methods.firstOrNull { it.name == "stopLoop" }?.let { runCatching { call(it, c) } }
    }

    override fun measureDelay(configJson: String, url: String): Long = runCatching {
        val m = lib().methods.first { it.name == "measureOutboundDelay" }
        (call(m, null, configJson, url) as Number).toLong()
    }.getOrDefault(-1L)

    /**
     * CoreController.queryAllOutboundTrafficStats() returns a JSON document of per-outbound counters.
     * The proxy outbound is tagged "proxy" (see XrayConfigBuilder); other tags are summed so the number
     * still moves when traffic takes a different route. Returns null when the core is not running or the
     * shape is not recognised, so callers can fall back to Android's per-app counters.
     */
    override fun queryTraffic(): CoreTraffic? {
        val c = controller ?: return null
        val m = c.javaClass.methods.firstOrNull { it.name == "queryAllOutboundTrafficStats" && it.parameterCount == 0 } ?: return null
        val raw = call(m, c) as? String ?: return null
        return CoreStats.parse(raw)
    }
}

/**
 * Xray's own delay probe: Libv2ray.measureOutboundDelay(configJson, url) builds a temporary core with a
 * single outbound and reports the real round-trip in ms. Needs no Android Context, so it can be called
 * from the UI layer for any server without disturbing the running core. -1 = failed or unavailable.
 */
object CoreDelay {
    /** Initialise the core env once per process; without it measureOutboundDelay cannot resolve geo data. */
    @Volatile private var envReady = false

    fun measure(ctx: Context, configJson: String, url: String): Long = try {
        if (!envReady) { LibXrayEngine(ctx).ensureEnv(ctx); envReady = true }
        val l = Class.forName("libv2ray.Libv2ray")
        val m = l.methods.first { it.name == "measureOutboundDelay" }
        (m.invoke(null, configJson, url) as Number).toLong()
    } catch (e: Throwable) { -1L }
}

/**
 * Parser for the core's traffic-statistics JSON.
 *
 * The keys are `uplink` / `downlink` (Xray's own stat names, confirmed against the strings in
 * libgojni.so) - NOT `up` / `down`. Matching the wrong names silently returns null, which is why an
 * earlier version of this showed no counter at all. Both spellings are accepted so a future core
 * rename cannot break it again.
 */
object CoreStats {
    private val num = Regex("\"?(up|down|uplink|downlink)\"?\\s*:\\s*(\\d+)", RegexOption.IGNORE_CASE)

    fun parse(raw: String): CoreTraffic? {
        val text = raw.trim()
        if (text.isEmpty() || text == "{}" || text.startsWith("error", true)) return null
        var up = 0L; var down = 0L; var seen = false
        num.findAll(text).forEach { m ->
            val v = m.groupValues[2].toLongOrNull() ?: return@forEach
            when (m.groupValues[1].lowercase()) {
                "up", "uplink" -> up += v
                else -> down += v
            }
            seen = true
        }
        return if (seen) CoreTraffic(up, down) else null
    }
}
