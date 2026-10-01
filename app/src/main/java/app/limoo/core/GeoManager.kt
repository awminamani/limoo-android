package app.limoo.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads geoip.dat / geosite.dat (routing data) with mirror fallback, checksum check and atomic replace. */
object GeoManager {
    val FILES = listOf("geoip.dat", "geosite.dat")
    /** Base URLs tried in order. First entry = GitHub release, second = jsDelivr mirror (often reachable when GitHub is not). */
    val SOURCES = mapOf(
        "chocolate4u" to listOf("https://github.com/Chocolate4U/Iran-v2ray-rules/releases/latest/download/", "https://cdn.jsdelivr.net/gh/Chocolate4U/Iran-v2ray-rules@release/"),
        "loyalsoldier" to listOf("https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/", "https://cdn.jsdelivr.net/gh/Loyalsoldier/v2ray-rules-dat@release/"),
    )

    data class Progress(val running: Boolean = false, val file: String = "", val percent: Int = 0, val error: String? = null)
    val progress = MutableStateFlow(Progress())
    private val mutex = Mutex()

    fun dir(ctx: Context) = File(ctx.filesDir, "geo").apply { mkdirs() }
    fun ready(ctx: Context) = FILES.all { File(dir(ctx), it).length() > 100_000 }
    fun ageDays(ctx: Context): Long = FILES.minOf { File(dir(ctx), it).lastModified() }.let { (System.currentTimeMillis() - it) / 86_400_000 }

    /** Makes sure routing data exists (and is at most maxAgeDays old if a download is possible). True when usable files are present. */
    suspend fun ensure(ctx: Context, source: String, maxAgeDays: Long = Long.MAX_VALUE): Boolean = mutex.withLock {
        if (ready(ctx) && ageDays(ctx) <= maxAgeDays) return true
        runCatching { download(ctx, source) }.onFailure { progress.value = Progress(error = it.message ?: "Download failed") }
        ready(ctx)
    }

    suspend fun forceUpdate(ctx: Context, source: String): Boolean = mutex.withLock {
        runCatching { download(ctx, source) }.onFailure { progress.value = Progress(error = it.message ?: "Download failed") }.isSuccess
    }

    private suspend fun download(ctx: Context, source: String) = withContext(Dispatchers.IO) {
        val bases = SOURCES[source] ?: SOURCES.getValue("chocolate4u")
        val tmp = FILES.associateWith { File(dir(ctx), "$it.tmp") }
        try {
            FILES.forEachIndexed { i, name ->
                progress.value = Progress(true, name, 0)
                var last: Exception? = null; var done = false
                for (base in bases) {
                    try { fetch(base + name, tmp.getValue(name)) { p -> progress.value = Progress(true, name, (i * 100 + p) / FILES.size) }; verify(base + name, tmp.getValue(name)); done = true; break }
                    catch (e: Exception) { last = e }
                }
                if (!done) throw last ?: Exception("Download failed")
            }
            FILES.forEach { tmp.getValue(it).let { t -> File(dir(ctx), it).let { f -> f.delete(); if (!t.renameTo(f)) throw Exception("Could not save $it") } } }
            progress.value = Progress()
        } catch (e: Exception) {
            tmp.values.forEach { it.delete() }; progress.value = Progress(error = e.message ?: "Download failed"); throw e
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000; setRequestProperty("User-Agent", "Limoo/0.2")
    }

    private fun fetch(url: String, out: File, onPercent: (Int) -> Unit) {
        val c = open(url)
        if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode} for ${url.substringAfter("://").substringBefore('/')}")
        val total = c.contentLengthLong; var read = 0L
        c.inputStream.use { i -> out.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); read += n; if (total > 0) onPercent((read * 100 / total).toInt()) }
        } }
        if (out.length() < 100_000) throw Exception("File too small - bad response")
    }

    /** Checks the published .sha256sum when the source has one; silently skipped when it doesn't. */
    private fun verify(url: String, file: File) {
        val expected = try { open("$url.sha256sum").takeIf { it.responseCode == 200 }?.inputStream?.bufferedReader()?.readText()?.trim()?.substringBefore(' ')?.lowercase() } catch (e: Exception) { null }
        if (expected.isNullOrEmpty() || expected.length != 64) return
        val md = MessageDigest.getInstance("SHA-256"); file.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        if (md.digest().joinToString("") { "%02x".format(it) } != expected) throw Exception("Checksum mismatch for ${file.name.removeSuffix(".tmp")}")
    }
}
