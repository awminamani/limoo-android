package app.limoo.core

import android.content.Context
import android.provider.Settings
import app.limoo.model.AppSettings
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

interface CoreEngine {
    fun start(configJson: String, tunFd: Int, st: AppSettings)   // tunFd = -1 in proxy-only mode
    fun stop()
    fun measureDelay(configJson: String, url: String): Long
}

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

    override fun start(configJson: String, tunFd: Int, st: AppSettings) {
        stop()
        val l = lib()
        val key = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "limoo"
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

    override fun stop() {
        val c = controller ?: return; controller = null
        c.javaClass.methods.firstOrNull { it.name == "stopLoop" }?.let { runCatching { call(it, c) } }
    }

    override fun measureDelay(configJson: String, url: String): Long = runCatching {
        val m = lib().methods.first { it.name == "measureOutboundDelay" }
        (call(m, null, configJson, url) as Number).toLong()
    }.getOrDefault(-1L)
}
