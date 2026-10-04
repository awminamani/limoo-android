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
 *
 * DATA LOSS THIS PREVENTS
 *
 * If decryption fails, getString returns the default - `"[]"` for the server list - and the app loads an
 * EMPTY list. That looked harmless, and was the opposite: the next persist() wrote that empty list back,
 * REPLACING a valid encrypted blob with `enc1:` + base64("[]"). One failed decryption destroyed every
 * server the user had, and the `.unreadable` copy was the only remaining trace.
 *
 * So a key that failed to decrypt is recorded in [unreadable] and REFUSES to be written until the user
 * deals with it. "We could not read your data" must never quietly become "your data is gone".
 */
class SecurePrefs private constructor(private val base: SharedPreferences, private val secure: Set<String>) : SharedPreferences {
    companion object {
        private const val ALIAS = "limoo_store_v1"
        private const val PREFIX = "enc1:"
        fun wrap(base: SharedPreferences, secureKeys: Set<String>): SharedPreferences = SecurePrefs(base, secureKeys)
    }

    @Volatile private var cached: SecretKey? = null

    /**
     * Keys whose blob exists but could not be decrypted. While a key is in here, writes to it are
     * REFUSED - see [Ed.putString]. Cleared only by an explicit reset or a successful read.
     */
    private val unreadable = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** True while any protected key is known-unreadable, i.e. the user must be warned. */
    val hasUnreadable: Boolean get() = unreadable.isNotEmpty()

    /** Which keys are currently known-unreadable, for a specific banner instead of a generic one. */
    fun unreadableKeys(): Set<String> = synchronized(unreadable) { unreadable.toSet() }

    /**
     * Drop the unreadable blob for a key and let the caller decide what happens next.
     *
     * This is the escape hatch the UI needs: "my saved servers could not be decrypted" is only useful if
     * the user can choose to discard it deliberately. Called by an explicit user action, never by a
     * failed read.
     */
    fun clearUnreadable(key: String) {
        unreadable.remove(key)
        base.edit().remove("$key.unreadable").apply()
    }

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
        if (plain == null) {
            // Preserve the blob AND refuse future writes to this key. Previously only the blob was kept,
            // so the next save destroyed it.
            base.edit().putString("$key.unreadable", v).apply()
            unreadable.add(key)
            Crash.log("secure prefs: '$key' could not be decrypted; writes refused", null)
        }
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
            if (key != null && key in secure && key in unreadable) {
                // Writing here would overwrite an undecryptable blob with whatever the app currently
                // holds - and what the app holds, right now, is a DEFAULT, not the user's data. Refusing
                // keeps the original recoverable via .unreadable until the user chooses to discard it.
                Crash.log("secure prefs: refused write to unreadable key '$key'", null)
                return this
            }
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
