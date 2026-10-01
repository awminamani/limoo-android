package app.limoo.format

import android.net.Uri
import android.util.Base64
import app.limoo.model.Server
import org.json.JSONObject

/** Server -> standard share link (what other clients and QR scanners understand). */
object LinkBuilder {
    fun build(s: Server): String = when (s.protocol) {
        "vmess" -> vmess(s)
        "shadowsocks" -> "ss://" + Base64.encodeToString("${s.method}:${s.uuid}".toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) + "@${host(s)}:${s.port}#" + Uri.encode(s.name)
        else -> std(s)
    }

    private fun host(s: Server) = if (':' in s.host) "[${s.host}]" else s.host

    private fun std(s: Server): String {
        val q = linkedMapOf("type" to s.network, "security" to s.security, "flow" to s.flow)
        if (s.security != "none") { q["sni"] = s.sni; q["fp"] = s.fp; q["alpn"] = s.alpn; if (s.allowInsecure) q["allowInsecure"] = "1" }
        if (s.security == "reality") { q["pbk"] = s.pbk; q["sid"] = s.sid; q["spx"] = s.spx }
        when (s.network) {
            "ws", "httpupgrade" -> { q["path"] = s.path; q["host"] = s.hostHeader }
            "xhttp" -> { q["path"] = s.path; q["host"] = s.hostHeader; q["mode"] = s.xhttpMode }
            "grpc" -> q["serviceName"] = s.serviceName
        }
        val query = q.filter { it.value.isNotEmpty() }.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
        return "${s.protocol}://${Uri.encode(s.uuid)}@${host(s)}:${s.port}?$query#${Uri.encode(s.name)}"
    }

    private fun vmess(s: Server): String {
        val j = JSONObject().put("v", "2").put("ps", s.name).put("add", s.host).put("port", s.port).put("id", s.uuid).put("aid", 0)
            .put("scy", s.method.ifEmpty { "auto" }).put("net", s.network).put("type", "none").put("host", s.hostHeader).put("path", s.path)
            .put("tls", if (s.security == "tls") "tls" else "").put("sni", s.sni).put("alpn", s.alpn).put("fp", s.fp)
        return "vmess://" + Base64.encodeToString(j.toString().toByteArray(), Base64.NO_WRAP)
    }
}
