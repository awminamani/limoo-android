package app.limoo.format

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric: LinkParser uses android.net.Uri and android.util.Base64. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LinkParserTest {
    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

    @Test fun vlessWsTls() {
        val s = LinkParser.parse("vless://11111111-2222-3333-4444-555555555555@example.com:443?type=ws&security=tls&sni=a.com&path=%2Fws&host=cdn.a.com#My%20Server")!!
        assertEquals("vless", s.protocol); assertEquals("example.com", s.host); assertEquals(443, s.port)
        assertEquals("ws", s.network); assertEquals("tls", s.security); assertEquals("a.com", s.sni)
        assertEquals("/ws", s.path); assertEquals("cdn.a.com", s.hostHeader); assertEquals("My Server", s.name)
    }

    @Test fun vlessReality() {
        val s = LinkParser.parse("vless://uuid@1.2.3.4:8443?security=reality&pbk=PUB&sid=ab&sni=www.speedtest.net&flow=xtls-rprx-vision&fp=firefox#r")!!
        assertEquals("reality", s.security); assertEquals("PUB", s.pbk); assertEquals("ab", s.sid)
        assertEquals("xtls-rprx-vision", s.flow); assertEquals("firefox", s.fp); assertEquals("tcp", s.network)
    }

    @Test fun trojanDefaultsToTls() {
        val s = LinkParser.parse("trojan://secret@t.example.com:443#t")!!
        assertEquals("trojan", s.protocol); assertEquals("tls", s.security); assertEquals("secret", s.uuid)
    }

    @Test fun vmessBase64Json() {
        val j = """{"v":"2","ps":"vm","add":"v.example.com","port":"8080","id":"abc","net":"ws","tls":"tls","path":"/p","host":"h"}"""
        val s = LinkParser.parse("vmess://" + b64(j))!!
        assertEquals("vmess", s.protocol); assertEquals("v.example.com", s.host); assertEquals(8080, s.port)
        assertEquals("ws", s.network); assertEquals("tls", s.security); assertEquals("/p", s.path); assertEquals("vm", s.name)
    }

    @Test fun shadowsocksSip002() {
        val s = LinkParser.parse("ss://" + b64("aes-256-gcm:pw") + "@5.6.7.8:8388#ss1")!!
        assertEquals("shadowsocks", s.protocol); assertEquals("aes-256-gcm", s.method); assertEquals("pw", s.uuid)
        assertEquals("5.6.7.8", s.host); assertEquals(8388, s.port); assertEquals("ss1", s.name)
    }

    @Test fun base64SubscriptionBody() {
        val body = b64("trojan://a@x.com:443#1\nnot a link\ntrojan://b@y.com:443#2")
        assertEquals(listOf("x.com", "y.com"), LinkParser.parseMany(body).map { it.host })
    }

    @Test fun unknownSchemeIsNull() { assertNull(LinkParser.parse("https://example.com")) }
}
