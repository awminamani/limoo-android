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

/**
 * Appearance a shared config can carry, so a receiver can adopt the sender's look without adopting their
 * whole setup. Deliberately a small closed set: theme, accent, and how much the background is dimmed. It is
 * NOT the user's chosen image - a `content://` URI means nothing on another device, so only the dim/blur
 * that makes text legible travels with the file.
 */
@Serializable
data class LimooAppearance(
    val theme: String = "system",                    // system | light | dark
    val accent: String = "mono",
    val bgDim: Float = 0.45f, val bgBlur: Float = 0f,
    val haptics: Boolean = true, val privacyMode: Boolean = false,
)

/**
 * Plain payload of a .limoo file.
 *
 * `limoo: 1` is the original shape: servers, subscription URLs, optional settings. It is still read, and
 * everything added since then is optional with a default, so a v1 file opens unchanged in this version and
 * a v2 file opened by a v1 build still yields its servers (the unknown keys are ignored).
 */
@Serializable
data class LimooPayload(
    val limoo: Int = 2,
    val name: String = "", val note: String = "",
    val expires: Long = 0,                       // epoch seconds, 0 = never
    val servers: List<Server> = emptyList(), val subscriptions: List<String> = emptyList(),
    val settings: AppSettings? = null,           // present in full backups
    /** v2: the look the sender chose. Null in v1 files and in a plain server share. */
    val appearance: LimooAppearance? = null,
    /** v2: tags/labels applied to the servers on import, so a share can carry its own organisation. */
    val tags: List<String> = emptyList(),
    /** v2: which group the servers land in. Empty keeps the receiver's own grouping. */
    val group: String = "",
)

/** Password-protected wrapper: AES-256-GCM, key = PBKDF2-HMAC-SHA256(password, salt, iter). */
@Serializable
data class LimooEnvelope(
    val limoo: Int = 2, val encrypted: Boolean = true, val kdf: String = "pbkdf2-sha256",
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

    /** The look a payload carries, as an [AppSettings] patch. Empty when the file has no appearance block. */
    fun appearancePatch(p: LimooPayload): AppSettings? = p.appearance?.let {
        AppSettings(theme = it.theme, accent = it.accent, bgDim = it.bgDim, bgBlur = it.bgBlur,
            haptics = it.haptics, privacyMode = it.privacyMode)
    }

    /** Builds the appearance block from the current settings, so Export can carry the look. */
    fun appearanceOf(st: AppSettings) = LimooAppearance(
        theme = st.theme, accent = st.accent, bgDim = st.bgDim, bgBlur = st.bgBlur,
        haptics = st.haptics, privacyMode = st.privacyMode,
    )
}