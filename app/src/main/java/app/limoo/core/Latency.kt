package app.limoo.core

import android.content.Context
import app.limoo.model.AppSettings
import app.limoo.model.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.*

object Latency {
    /**
     * Probe results are three distinct states, not two. Collapsing them is what made
     * "Remove unreachable" destructive: a probe that is merely UNSUPPORTED returned the same 0 as a
     * genuine timeout, so a feature-flagged or unavailable probe deleted every server it touched.
     *
     *  - [UNKNOWN] the probe could not run at all (unsupported core, no network, not attempted yet)
     *  - [TIMEOUT] the probe ran and the server did not answer
     *  - anything &gt; 0 is a measured latency in ms
     *
     * Only [TIMEOUT] is evidence a server is dead.
     */
    const val UNKNOWN = -1L
    const val TIMEOUT = 0L

    /** TCP connect time in ms (DNS excluded). [TIMEOUT] on failure, never [UNKNOWN]. */
    suspend fun tcp(s: Server, timeoutMs: Int = 4000): Long = withContext(Dispatchers.IO) {
        try {
            val addr = InetSocketAddress(InetAddress.getByName(s.host), s.port)
            val t0 = System.nanoTime()
            Socket().use { it.connect(addr, timeoutMs) }
            ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
        } catch (e: Exception) { TIMEOUT }
    }

    /**
     * Real end-to-end delay for ONE server, measured by the core itself: spins a throwaway core with just
     * this outbound and runs Xray's own observatory probe. Unlike [tcp] this includes TLS handshake and
     * the server's real RTT, which is what a user means by "ping". -1 = failed/unsupported.
     */
    suspend fun real(ctx: Context, s: Server, st: AppSettings, url: String): Long = withContext(Dispatchers.IO) {
        CoreDelay.measure(ctx, XrayConfigBuilder.buildProbe(s, st), url)
    }

    /** Real end-to-end delay: HTTP request through the running core's local SOCKS inbound. 0 = failed. */
    suspend fun viaProxy(socksPort: Int, url: String, timeoutMs: Int = 8000): Long = withContext(Dispatchers.IO) {
        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val t0 = System.nanoTime()
            (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = timeoutMs; readTimeout = timeoutMs; instanceFollowRedirects = false; useCaches = false
            }.let { c -> c.responseCode; c.disconnect() }
            ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
        } catch (e: Exception) { 0L }
    }
}
