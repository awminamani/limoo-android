package app.limoo.core

/**
 * Turns a raw Xray core error into something a person can act on.
 *
 * The core's messages are accurate but written for the config file, not for a phone screen. They are
 * also long: the reason almost always arrives at the END of a sentence about a JSON path. Home used to
 * show `.take(120)` of one, which cut the message off mid-word at exactly the point where the cause
 * starts - "failed to build outbound config with tag dire" says nothing, while the rest of the same
 * line names the actual problem.
 *
 * So this maps the failures that have a known user-facing fix, and deliberately returns null for
 * anything unrecognised: a wrong guess is worse than the raw text, which is always shown underneath.
 *
 * Pure and side-effect free, so it is unit-tested rather than trusted.
 */
object ErrorHints {

    data class Hint(val title: String, val detail: String)

    /**
     * One rule: a predicate over the raw message, and the hint it produces.
     *
     * A named class rather than `Pair<(String) -> Boolean, Hint>` written with an infix `to`: pairing a
     * bare leading lambda with `to` infers `Function0`, not `Function1`, and the whole list fails to
     * compile with a cascade of confusing "Unresolved reference 'it'" errors.
     */
    private class Rule(val matches: (String) -> Boolean, val hint: Hint)

    /**
     * Ordered most specific first. This ordering is load-bearing and covered by a test: the raw text
     * routinely contains BOTH a generic phrase ("failed to build") and the real cause, so a catch-all
     * evaluated first would win and report the useless message.
     */
    private val RULES = listOf(
        Rule(
            { it.contains("unsupported domain strategy", true) },
            Hint(
                "Invalid domain strategy",
                "This version of the Xray core does not accept the domain strategy in your settings. " +
                    "Set Settings -> Routing -> Domain strategy to AsIs, or update the core.",
            ),
        ),
        Rule(
            {
                it.contains("publickey", true) ||
                    it.contains("public_key", true) ||
                    (it.contains("reality", true) && it.contains("invalid", true))
            },
            Hint(
                "Server REALITY key looks wrong",
                "The public key, short ID or SNI on this server does not match the server's configuration. " +
                    "Re-copy the link from your provider and import it again.",
            ),
        ),
        Rule(
            {
                it.contains("address already in use", true) ||
                    (it.contains("listen tcp", true) && it.contains("bind", true))
            },
            Hint(
                "Local port already in use",
                "Another app is using one of Limoo's local proxy ports (10808 or 10809 by default). " +
                    "Close the other proxy app, or change the port in Settings.",
            ),
        ),
        Rule(
            {
                it.contains("no such host", true) ||
                    it.contains("unresolved", true) ||
                    (it.contains("dns", true) && it.contains("fail", true))
            },
            Hint(
                "Could not resolve the server address",
                "The hostname in this server's link did not resolve. Check the address, or your DNS.",
            ),
        ),
        Rule(
            {
                it.contains("certificate", true) ||
                    (it.contains("tls", true) && it.contains("handshake", true))
            },
            Hint(
                "TLS handshake failed",
                "The server's certificate or SNI did not match. Check the SNI and fingerprint in the server editor.",
            ),
        ),
        Rule(
            { it.contains("timeout", true) || it.contains("i/o timeout", true) },
            Hint(
                "Connection timed out",
                "The server did not answer in time. It may be down, blocked, or the address and port may be wrong.",
            ),
        ),
        Rule(
            { it.contains("refused", true) || it.contains("connection reset", true) },
            Hint(
                "Connection refused",
                "Nothing accepted the connection on that port. Check the address and port, and that the server is running.",
            ),
        ),
        Rule(
            { it.contains("geoip", true) || it.contains("geosite", true) },
            Hint(
                "Routing data missing",
                "A routing preset needs the geoip/geosite data files, which could not be downloaded. " +
                    "Connect without routing rules, or try again on a network that can reach GitHub.",
            ),
        ),
        Rule(
            { it.contains("permission", true) && it.contains("vpn", true) },
            Hint(
                "VPN permission missing",
                "Android revoked the VPN permission for Limoo. Reconnect and accept the prompt.",
            ),
        ),
        Rule(
            {
                it.contains("config error", true) ||
                    it.contains("failed to parse json", true) ||
                    it.contains("failed to build", true)
            },
            Hint(
                "The core rejected the configuration",
                "This is a bug in Limoo's config builder, not your settings. Copy the details below and " +
                    "report them - the raw message underneath says which part was rejected.",
            ),
        ),
    )

    /** A short, plain-language explanation, or null when nothing is recognised. */
    fun forError(raw: String?): Hint? {
        val s = raw?.takeIf { it.isNotBlank() } ?: return null
        return RULES.firstOrNull { it.matches(s) }?.hint
    }

    /** Short label for the collapsed state; the full detail is shown when expanded. */
    fun title(raw: String?): String? = forError(raw)?.title
}