package app.limoo.format

import app.limoo.model.AppSettings
import app.limoo.model.Server

/**
 * Everything an import source (clipboard, QR, file, link) can contain, parsed but not yet applied.
 *
 * [appearance] and [group] are separate from [settings] on purpose. A `.limoo` v2 file can carry a small
 * "look" (theme, accent, dim) without carrying the sender's entire configuration, and the import preview
 * offers those as separate choices so receiving a config never silently overwrites your setup.
 */
data class ImportPreview(
    val source: String, val title: String = "", val note: String = "", val expires: Long = 0,
    val servers: List<Server> = emptyList(), val subUrls: List<String> = emptyList(),
    val settings: AppSettings? = null, val needsPassword: Boolean = false, val raw: String = "",
    val appearance: LimooAppearance? = null, val group: String = "", val tags: List<String> = emptyList(),
    val spec: Int = 1,
)

object Importer {
    /** Returns null when [text] holds nothing importable. Throws LimooFile.BadPassword for a wrong password. */
    fun parse(text: String, source: String, password: String? = null): ImportPreview? {
        val t = text.trim()
        if (t.isEmpty()) return null
        try {
            LimooFile.parseAny(t, password)?.let { p ->
                return ImportPreview(
                    source, p.name, p.note, p.expires, p.servers, p.subscriptions, p.settings,
                    raw = t, appearance = p.appearance, group = p.group, tags = p.tags, spec = p.limoo,
                )
            }
        } catch (e: LimooFile.NeedsPassword) { return ImportPreview(source, needsPassword = true, raw = t) }
        if ((t.startsWith("http://") || t.startsWith("https://")) && t.lines().size == 1) return ImportPreview(source, subUrls = listOf(t), raw = t)
        val servers = LinkParser.parseMany(t)
        return if (servers.isEmpty()) null else ImportPreview(source, servers = servers, raw = t)
    }
}