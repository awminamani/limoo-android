package app.limoo.core

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Last-resort crash breadcrumb.
 *
 * A VPN client that dies on connect is hard to diagnose without logcat, so anything suspicious is appended
 * to a small file in the app's files dir. On the next launch the Home screen surfaces the last entry, which
 * turns "it just crashed" into a named failure. Never throws.
 */
object Crash {
    private const val FILE = "last_crash.txt"
    private const val MAX = 4000

    fun log(where: String, t: Throwable?) {
        try {
            val ctx = appContext ?: return
            val text = buildString {
                append(android.os.System.currentTimeMillis()).append("  ").append(where).append('
')
                if (t != null) {
                    append(t.javaClass.name).append(": ").append(t.message ?: "").append('
')
                    append(Log.getStackTraceString(t).take(MAX)).append('
')
                }
            }
            File(ctx.filesDir, FILE).appendText(text)
        } catch (ignored: Throwable) { /* a crash logger must never itself crash */ }
    }

    /** Set by the Application so helpers can log without a Context. */
    @Volatile var appContext: Context? = null

    fun last(): String? = try {
        appContext?.let { File(it.filesDir, FILE).takeIf { f -> f.length() > 0 }?.readText()?.takeLast(MAX) }
    } catch (t: Throwable) { null }

    fun clear() { runCatching { appContext?.let { File(it.filesDir, FILE).delete() } } }
}
