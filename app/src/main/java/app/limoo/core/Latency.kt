package app.limoo.core

import app.limoo.model.Server
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.*

object Latency {
    /** TCP connect time in ms (DNS excluded). 0 = failed/timeout. */
    suspend fun tcp(s: Server, timeoutMs: Int = 4000): Long = withContext(Dispatchers.IO) {
        try {
            val addr = InetSocketAddress(InetAddress.getByName(s.host), s.port)
            val t0 = System.nanoTime()
            Socket().use { it.connect(addr, timeoutMs) }
            ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)
        } catch (e: Exception) { 0L }
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
