package app.limoo.core

import app.limoo.model.Server

/**
 * Rejects a server that Xray cannot build, BEFORE the core is asked to.
 *
 * Why this exists: the core's own errors are accurate but arrive as a JSON path and a sentence about
 * outbound construction, long after the user has tapped connect. "failed to build outbound config with
 * tag proxy > infra/conf: vless without TLS or other encryption is prohibited" is true and useless at
 * the moment it arrives.
 *
 * Every rule here was discovered by RUNNING `xray run -test` over a generated matrix
 * (tools/check-xray-configs.sh), not by reading documentation or memory. That distinction matters:
 * three of these - the encryption requirement, the REALITY transport list, and the shape of the REALITY
 * public key - are enforced only while the core builds the config, so they are invisible in the JSON
 * and would never be caught by a shape test.
 *
 * Pure and side-effect free: no Context, no core, no network. Returns every problem rather than the
 * first, so an import preview can show all of them at once, and returns an empty list when the server
 * is fine.
 */
object ConfigValidator {

    /** Values Xray v26 accepts for `streamSettings.network`. */
    private val NETWORKS = setOf("tcp", "ws", "grpc", "xhttp", "httpupgrade")

    /** Values Xray v26 accepts for `streamSettings.security`. */
    private val SECURITIES = setOf("none", "tls", "reality")

    private val PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks")

    /**
     * REALITY is only implemented over raw TCP, XHTTP and gRPC. Measured, not assumed:
     * "REALITY only supports RAW, XHTTP and gRPC for now."
     */
    private val REALITY_NETWORKS = setOf("tcp", "xhttp", "grpc")

    /**
     * VLESS and Trojan require an encrypted transport. Measured:
     * "vless without TLS or other encryption is prohibited unless the server address is a private IP
     * or domain" - and the same rule for trojan.
     */
    private val NEEDS_ENCRYPTION = setOf("vless", "trojan")

    /** Shadowsocks ciphers the core accepts. An empty method is rejected outright. */
    private val SS_METHODS = setOf(
        "aes-256-gcm", "aes-128-gcm", "chacha20-poly1305", "chacha20-ietf-poly1305",
        "xchacha20-poly1305", "xchacha20-ietf-poly1305", "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305", "none", "plain",
    )

    private val UUID_RE = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")

    /** Base64url without padding, as REALITY public keys are written. */
    private val B64URL_RE = Regex("""^[A-Za-z0-9_-]+$""")

    /** True for an IPv4 literal or anything bracketed/braced, i.e. not a DNS name. */
    private fun isLiteralAddress(host: String): Boolean {
        if (host.startsWith("[") || host.startsWith("{")) return true       // IPv6 in a link
        if (host.all { it.isDigit() || it == '.' }) return true
        return Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)
    }

    /**
     * All problems with this server, phrased for a user. Empty means Xray will accept it.
     *
     * Deliberately reports everything: an import preview that reveals one problem at a time makes the
     * user fix a link that is broken in four ways.
     */
    fun validate(s: Server): List<String> {
        val out = mutableListOf<String>()

        if (s.host.isBlank()) out += "Server address is empty."
        if (s.port !in 1..65535) out += "Port must be between 1 and 65535 (got ${s.port})."

        if (s.protocol !in PROTOCOLS) {
            out += "Unknown protocol '${s.protocol}'."
            return out                       // nothing below is meaningful
        }
        if (s.network !in NETWORKS) out += "Unknown transport '${s.network}'."
        if (s.security !in SECURITIES) out += "Unknown security '${s.security}'."

        // ---- credentials ----
        when (s.protocol) {
            "vless", "vmess" ->
                if (!UUID_RE.matches(s.uuid)) out += "The UUID is not a valid UUID."
            "trojan" ->
                if (s.uuid.isBlank()) out += "Trojan needs a password."
            "shadowsocks" -> {
                if (s.uuid.isBlank()) out += "Shadowsocks needs a password."
                if (s.method.isBlank()) out += "Shadowsocks needs an encryption method."
                else if (s.method.lowercase() !in SS_METHODS) {
                    out += "Shadowsocks method '${s.method}' is not supported."
                }
            }
        }

        // ---- encryption requirement (measured against Xray v26) ----
        if (s.protocol in NEEDS_ENCRYPTION && s.security == "none" && !isLiteralAddress(s.host)) {
            out += "${s.protocol.replaceFirstChar { it.uppercase() }} needs TLS or REALITY; " +
                "this server has neither. Check the link's security parameter."
        }

        // ---- REALITY ----
        if (s.security == "reality") {
            if (s.sni.isBlank()) out += "REALITY needs a server name (SNI)."
            // The public key is what Xray calls `password`: 43 base64url chars = 32 bytes.
            if (s.pbk.isBlank()) {
                out += "REALITY needs a public key."
            } else {
                if (s.pbk.length != 43 || !B64URL_RE.matches(s.pbk)) {
                    out += "The REALITY public key should be 43 base64url characters " +
                        "(this one is ${s.pbk.length})."
                }
            }
            if (s.sid.isNotBlank()) {
                if (s.sid.length > 16 || s.sid.length % 2 != 0 || !s.sid.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    out += "The REALITY short ID must be hex, even length, and at most 16 characters."
                }
            }
            if (s.network !in REALITY_NETWORKS) {
                out += "REALITY only works over TCP, XHTTP or gRPC - not ${s.network}."
            }
            if (s.flow.isNotEmpty()) out += "REALITY does not use a flow setting."
        }

        // ---- transport paths ----
        if (s.network in setOf("ws", "httpupgrade", "xhttp")) {
            if (s.path.isNotEmpty() && !s.path.startsWith("/")) out += "The path must start with '/'."
        }
        if (s.network == "grpc" && s.serviceName.isBlank()) {
            // Not fatal to the core, but a blank serviceName connects nowhere, which is worse.
            out += "gRPC needs a service name."
        }

        // ---- flow ----
        // flow is only meaningful on vless over tcp/xhttp with TLS or REALITY, and it conflicts with mux.
        if (s.flow.isNotEmpty()) {
            if (s.protocol != "vless") out += "Only VLESS uses a flow setting."
            else if (s.network !in setOf("tcp", "xhttp")) out += "Flow is not supported over ${s.network}."
            else if (s.security == "none") out += "Flow needs TLS or REALITY."
        }

        return out
    }

    /** True when the server is safe to hand to the core. */
    fun isValid(s: Server): Boolean = validate(s).isEmpty()
}