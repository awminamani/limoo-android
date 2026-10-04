package app.limoo.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Removes secrets from anything about to be written to `Crash.log` or shared in a diagnostic export.
 *
 * Subscription URLs are the sharpest edge here: they are bearer tokens. Anyone holding one can read the
 * provider's whole server list, and URLs land in a crash log for reasons that have nothing to do with the
 * secret - a config parse error, a failed connect, a stack trace. A core error message quotes the config
 * back at you, so `address` and `id` values appear there too.
 *
 * Deliberately conservative in one direction and not the other: it redacts generously (over-redacting a
 * hostname costs nothing) and never tries to be clever about the shape of a secret it does not recognise.
 * An unrecognised secret stays visible; that is the honest failure mode for a redaction filter, and the
 * alternative - guessing - produces false confidence.
 */
object Redact {

    /** Keys whose values are credentials in any Xray or Limoo config. */
    private val SECRET_KEYS = setOf(
        "id", "password", "publicKey", "pbk", "shortId", "sid", "spiderX", "spx",
        "privateKey", "passphrase", "secret", "token", "apiKey",
    )

    private val MASK = "***"

    /** Keys whose values are server addresses; only redacted when the user asked for privacy mode. */
    private val ADDRESS_KEYS = setOf("address", "host", "server", "ip", "domain", "sni", "serverName")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Redact a whole config document.
     *
     * `privateMode` also masks addresses and hostnames, for someone who does not want the log to reveal
     * which provider or which server they use.
     */
    fun config(text: String, privateMode: Boolean = false): String {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return text
        return runCatching { json.encodeToString(JsonObject.serializer(), walk(root, privateMode)) }
            .getOrDefault(text)
    }

    private fun walk(o: JsonObject, privateMode: Boolean): JsonObject =
        JsonObject(o.mapValues { (k, v) ->
            val child = v as? JsonObject
            when {
                child != null -> walk(child, privateMode)
                k in SECRET_KEYS -> jsonPrimitiveOf(MASK)
                privateMode && k in ADDRESS_KEYS -> jsonPrimitiveOf(MASK)
                // A user rule list can carry geo references, which are not secrets, but a custom rule
                // can also name a domain the user browses to. Left alone unless private mode.
                else -> v
            }
        })

    private fun jsonPrimitiveOf(s: String) = kotlinx.serialization.json.JsonPrimitive(s)

    /**
     * Strip credentials out of a subscription URL so it can be logged or shown safely.
     *
     * Keeps host and path shape so the line is still useful for diagnosis: the example
     * https://provider.example/sub/abcdef123456?token=x becomes https://provider.example/sub/MASKED.
     *
     * (The masked form is spelled out in words rather than literally, because a literal contains a slash
     * followed by asterisks - which Kotlin reads as the start of a nested comment and swallows the rest of
     * the file.)
     *
     * If the URL cannot be parsed it is replaced wholesale rather than passed through - a malformed string
     * in this position is more likely to be a pasted secret than a typo.
     */
    fun subscriptionUrl(raw: String): String {
        val u = runCatching { java.net.URI(raw) }.getOrNull() ?: return MASK
        val path = u.path.orEmpty()
        val lastSegment = path.substringAfterLast('/')
        // A subscription token is normally the final path segment. A one-character segment is more likely
        // to be "/" than a secret, so it is left alone.
        val safePath = if (lastSegment.length >= 6) {
            path.substringBeforeLast('/') + "/" + MASK
        } else path
        val sb = StringBuilder()
        u.scheme?.let { sb.append(it).append("://") }
        sb.append(u.host.orEmpty())
        if (u.port > 0) sb.append(':').append(u.port)
        sb.append(safePath)
        // Query values are dropped wholesale: they are where bearer tokens usually live.
        if (u.query != null) sb.append('?').append(MASK)
        return sb.toString()
    }

    /**
     * Redact a free-text message, for anything that may embed a config or a URL.
     *
     * Line-based rather than clever: `enc1:` blobs are replaced outright (they are ciphertext of the user's
     * servers), and any `scheme://` token runs through [subscriptionUrl].
     */
    fun message(text: String, privateMode: Boolean = false): String {
        var s = text.replace(Regex("enc1:[A-Za-z0-9+/=_-]{8,}"), "enc1:$MASK")
        s = s.replace(Regex("(vless|vmess|trojan|ss|hy2|hysteria2?)://\\S+"), MASK)
        s = s.replace(Regex("https?://\\S+")) { m -> subscriptionUrl(m.value) }
        return s
    }
}
