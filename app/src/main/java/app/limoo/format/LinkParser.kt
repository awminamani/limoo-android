package app.limoo.format

import android.net.Uri
import android.util.Base64
import app.limoo.model.Server
import org.json.JSONObject

object LinkParser {
    fun parseMany(text: String): List<Server> {
        val body = text.trim().let { if ("://" !in it) runCatching { String(Base64.decode(it, Base64.DEFAULT)) }.getOrDefault(it) else it }
        return body.lineSequence().map { it.trim() }.mapNotNull { runCatching { parse(it) }.getOrNull() }.toList()
    }

    fun parse(l: String): Server? = when {
        l.startsWith("vless://") -> std(l, "vless")
        l.startsWith("trojan://") -> std(l, "trojan")
        l.startsWith("vmess://") -> vmess(l)
        l.startsWith("ss://") -> ss(l)
        else -> null
    }

    private fun std(link: String, proto: String): Server {
        val u = Uri.parse(link); fun q(k: String) = u.getQueryParameter(k) ?: ""
        return Server(
            name = u.fragment ?: "${u.host}:${u.port}", protocol = proto, host = u.host!!, port = u.port, uuid = u.userInfo ?: "",
            flow = q("flow"), network = q("type").ifEmpty { "tcp" },
            security = q("security").ifEmpty { if (proto == "trojan") "tls" else "none" },
            sni = q("sni"), alpn = q("alpn"), fp = q("fp").ifEmpty { "chrome" }, path = q("path"), hostHeader = q("host"),
            serviceName = q("serviceName"), xhttpMode = q("mode"), pbk = q("pbk"), sid = q("sid"), spx = q("spx"),
            allowInsecure = q("allowInsecure") == "1" || q("insecure") == "1",
        )
    }

    private fun vmess(link: String): Server {
        val j = JSONObject(String(Base64.decode(link.removePrefix("vmess://"), Base64.DEFAULT)))
        return Server(
            name = j.optString("ps", j.getString("add")), protocol = "vmess", host = j.getString("add"), port = j.get("port").toString().toInt(),
            uuid = j.getString("id"), method = j.optString("scy", "auto"), network = j.optString("net", "tcp"),
            security = if (j.optString("tls") == "tls") "tls" else "none", sni = j.optString("sni"), alpn = j.optString("alpn"),
            fp = j.optString("fp").ifEmpty { "chrome" }, path = j.optString("path"), hostHeader = j.optString("host"),
        )
    }

    private fun ss(link: String): Server {
        val name = Uri.decode(link.substringAfter('#', ""))
        val core = link.removePrefix("ss://").substringBefore('#').substringBefore('?')
        val (cred, hostPort) = if ('@' in core) core.substringBefore('@') to core.substringAfter('@')
        else String(Base64.decode(core, Base64.URL_SAFE or Base64.DEFAULT)).let { it.substringBefore('@') to it.substringAfter('@') }
        val c = if (':' in cred) cred else String(Base64.decode(cred, Base64.URL_SAFE or Base64.DEFAULT))
        return Server(name = name.ifEmpty { hostPort }, protocol = "shadowsocks", host = hostPort.substringBeforeLast(':'),
            port = hostPort.substringAfterLast(':').toInt(), method = c.substringBefore(':'), uuid = c.substringAfter(':'))
    }
}
