package app.limoo.core

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SharedPreferences wrapper that encrypts selected keys (servers, subscriptions) with an AES-256-GCM key held
 * in the Android Keystore. Values are stored as `enc1:` + base64(iv || ciphertext).
 *
 * - Migration is free: plain (old) values are read as-is and re-written encrypted on the next save.
 * - If the keystore is unavailable, writes fall back to plain text rather than losing data.
 * - If a value cannot be decrypted (e.g. restored onto another device by Auto Backup), the blob is kept
 *   under `<key>.unreadable` and the default is returned. Use Limoo's own Export backup to move devices.
 */
class SecurePrefs private constructor(private val base: SharedPreferences, private val secure: Set<String>) : SharedPreferences {
    companion object {
        private const val ALIAS = "limoo_store_v1"
        private const val PREFIX = "enc1:"
        fun wrap(base: SharedPreferences, secureKeys: Set<String>): SharedPreferences = SecurePrefs(base, secureKeys)
    }

    @Volatile private var cached: SecretKey? = null

    private fun key(): SecretKey {
        cached?.let { return it }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val k = (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
        cached = k
        return k
    }

    private fun encrypt(plain: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        PREFIX + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }.onFailure { Crash.log("secure prefs encrypt", it) }.getOrNull()

    private fun decrypt(v: String): String? = runCatching {
        val b = Base64.decode(v.removePrefix(PREFIX), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
        String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }.onFailure { Crash.log("secure prefs decrypt", it) }.getOrNull()

    override fun getString(key: String?, defValue: String?): String? {
        val v = base.getString(key, null) ?: return defValue
        if (!v.startsWith(PREFIX)) return v
        val plain = decrypt(v)
        if (plain == null) base.edit().putString("$key.unreadable", v).apply()
        return plain ?: defValue
    }

    override fun getAll(): MutableMap<String, *> = base.all
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = base.getStringSet(key, defValues)
    override fun getInt(key: String?, defValue: Int): Int = base.getInt(key, defValue)
    override fun getLong(key: String?, defValue: Long): Long = base.getLong(key, defValue)
    override fun getFloat(key: String?, defValue: Float): Float = base.getFloat(key, defValue)
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = base.getBoolean(key, defValue)
    override fun contains(key: String?): Boolean = base.contains(key)
    override fun edit(): SharedPreferences.Editor = Ed(base.edit())
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
        base.registerOnSharedPreferenceChangeListener(listener)
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) =
        base.unregisterOnSharedPreferenceChangeListener(listener)

    private inner class Ed(private val e: SharedPreferences.Editor) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            val out = if (value != null && key != null && key in secure) encrypt(value) ?: value else value
            e.putString(key, out); return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { e.putStringSet(key, values); return this }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor { e.putInt(key, value); return this }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor { e.putLong(key, value); return this }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { e.putFloat(key, value); return this }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { e.putBoolean(key, value); return this }
        override fun remove(key: String?): SharedPreferences.Editor { e.remove(key); return this }
        override fun clear(): SharedPreferences.Editor { e.clear(); return this }
        override fun commit(): Boolean = e.commit()
        override fun apply() = e.apply()
    }
}
