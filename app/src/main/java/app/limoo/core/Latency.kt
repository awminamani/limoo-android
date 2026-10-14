package app.limoo.core

import android.content.Context
import app.limoo.model.AppSettings
import app.limoo.model.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * TCP connect time in ms (DNS excluded). [TIMEOUT] when the server was resolved but did not
     * answer; [UNKNOWN] when the name itself could not be resolved - a resolver failure on the
     * phone's network says nothing about the server, and recording it as TIMEOUT used to mark
     * healthy servers dead whenever DNS hiccupped.
     *
     * All resolved addresses are considered (at most two): a single AAAA answer on a broken-IPv6
     * path used to burn the whole timeout and report a live server dead. When IPv6 is off in
     * settings, IPv4 answers are tried first.
     */
    suspend fun tcp(s: Server, timeoutMs: Int = 4000, ipv6: Boolean = true): Long = withContext(Dispatchers.IO) {
        val addrs = try {
            InetAddress.getAllByName(s.host).toList()
        } catch (e: Exception) {
            return@withContext UNKNOWN
        }
        if (addrs.isEmpty()) return@withContext UNKNOWN
        val ordered = if (ipv6) addrs else addrs.sortedBy { it is Inet6Address }
        for (a in ordered.take(2)) {
            try {
                val t0 = System.nanoTime()
                Socket().use { it.connect(InetSocketAddress(a, s.port), timeoutMs) }
                return@withContext ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
            } catch (e: Exception) {
                // fall through to the next address
            }
        }
        TIMEOUT
    }

    const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

    /**
     * Real end-to-end delay for ONE server, measured by the core itself: spins a throwaway core with just
     * this outbound and runs Xray's own observatory probe. Unlike [tcp] this includes TLS handshake and
     * the server's real RTT, which is what a user means by "ping".
     *
     * Bounded by twice the user's ping timeout (AppSettings documents the in-tunnel test at 2x): the
     * core call carries no timeout of its own, and without this bound one dead server stalled the
     * whole sweep for the AAR's internal duration - with concurrency 2, a list with a few dead
     * servers took minutes and looked like the ping feature was simply broken. The blocking core
     * call itself cannot be interrupted, but the coroutine, the semaphore and the UI are released.
     *
     * Returns [UNKNOWN] only when the probe cannot run at all (core without measureOutboundDelay);
     * a probe that ran and failed - including by timeout - is [TIMEOUT].
     */
    suspend fun real(ctx: Context, s: Server, st: AppSettings, url: String): Long = withContext(Dispatchers.IO) {
        if (!CoreDelay.supported(ctx)) return@withContext UNKNOWN
        val u = url.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: DEFAULT_TEST_URL
        val ms = try {
            withTimeoutOrNull(st.pingTimeoutMs.toLong() * 2) {
                CoreDelay.measure(ctx, XrayConfigBuilder.buildProbe(s, st), u)
            }
        } catch (e: Exception) {
            null
        } ?: return@withContext TIMEOUT
        if (ms > 0) ms else TIMEOUT
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
