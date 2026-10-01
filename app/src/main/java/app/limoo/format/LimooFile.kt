package app.limoo.format

import android.net.Uri
import app.limoo.model.AppSettings
import app.limoo.model.Server
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Plain payload of a .limoo file (spec v1). */
@Serializable
data class LimooPayload(
    val limoo: Int = 1, val name: String = "", val note: String = "",
    val expires: Long = 0,                       // epoch seconds, 0 = never
    val servers: List<Server> = emptyList(), val subscriptions: List<String> = emptyList(),
    val settings: AppSettings? = null,           // present in full backups
)

/** Password-protected wrapper: AES-256-GCM, key = PBKDF2-HMAC-SHA256(password, salt, iter). */
@Serializable
data class LimooEnvelope(
    val limoo: Int = 1, val encrypted: Boolean = true, val kdf: String = "pbkdf2-sha256",
    val iter: Int = 200_000, val salt: String, val iv: String, val data: String,
)

object LimooFile {
    const val EXT = "limoo"; const val MIME = "application/x-limoo"
    class NeedsPassword : Exception("This file is password protected")
    class BadPassword : Exception("Wrong password")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val b64 = Base64.getEncoder(); private val unb64 = Base64.getDecoder()

    private fun key(pw: String, salt: ByteArray, iter: Int) = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw.toCharArray(), salt, iter, 256)).encoded, "AES")

    fun encode(p: LimooPayload, password: String? = null): String {
        val plain = json.encodeToString(p)
        if (password.isNullOrEmpty()) return plain
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(password, salt, 200_000), GCMParameterSpec(128, iv)) }
        return json.encodeToString(LimooEnvelope(salt = b64.encodeToString(salt), iv = b64.encodeToString(iv), data = b64.encodeToString(c.doFinal(plain.toByteArray()))))
    }

    fun decode(text: String, password: String? = null): LimooPayload {
        val t = text.trim()
        val enc = json.parseToJsonElement(t).jsonObject["encrypted"]?.jsonPrimitive?.booleanOrNull == true
        if (!enc) return json.decodeFromString(t)
        if (password.isNullOrEmpty()) throw NeedsPassword()
        val e = json.decodeFromString<LimooEnvelope>(t)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(password, unb64.decode(e.salt), e.iter), GCMParameterSpec(128, unb64.decode(e.iv)))
        }
        return try { json.decodeFromString(String(c.doFinal(unb64.decode(e.data)))) } catch (x: AEADBadTagException) { throw BadPassword() }
    }

    /** limoo://import?d=<base64url(gzip(file text))> — same content as the file, shareable as a link. */
    fun toDeepLink(p: LimooPayload, password: String? = null): String {
        val bos = ByteArrayOutputStream(); GZIPOutputStream(bos).use { it.write(encode(p, password).toByteArray()) }
        return "limoo://import?d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray())
    }

    /** Accepts file text or a limoo:// link. Returns null when the input isn't a .limoo payload. */
    fun parseAny(input: String, password: String? = null): LimooPayload? {
        var text = input.trim()
        if (text.startsWith("limoo://")) {
            val d = Uri.parse(text).getQueryParameter("d") ?: return null
            text = GZIPInputStream(Base64.getUrlDecoder().decode(d).inputStream()).readBytes().decodeToString()
        }
        return if (text.startsWith("{")) decode(text, password) else null
    }
}
