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
    private const val ROTATE_AT = 64 * 1024L
    private const val NL = "\n"

    /** Set by the Application so helpers can log without a Context. */
    @Volatile var appContext: Context? = null

    fun log(where: String, t: Throwable?) {
        try {
            val ctx = appContext ?: return
            val sb = StringBuilder()
            sb.append(System.currentTimeMillis()).append("  ").append(where).append(NL)
            if (t != null) {
                sb.append(t.javaClass.name).append(": ").append(t.message ?: "").append(NL)
                sb.append(Log.getStackTraceString(t).take(MAX)).append(NL)
            }
            val f = File(ctx.filesDir, FILE)
            // Rotate. The file was only ever READ back as its last MAX chars, but it was APPENDED to
            // without limit, so it grew for the life of the install while the visible window never
            // moved. Keep a bounded tail and one previous generation, so a failure that happened just
            // before a rotation is still recoverable.
            if (f.length() > ROTATE_AT) {
                val old = File(ctx.filesDir, "$FILE.1")
                runCatching { old.delete() }
                runCatching { f.renameTo(old) }
            }
            f.appendText(sb.toString())
        } catch (ignored: Throwable) {
            // A crash logger must never itself crash.
        }
    }

    fun last(): String? = try {
        appContext?.let { ctx -> File(ctx.filesDir, FILE).takeIf { it.length() > 0 }?.readText()?.takeLast(MAX) }
    } catch (t: Throwable) { null }

    fun clear() { runCatching { appContext?.let { File(it.filesDir, FILE).delete() } } }
}
