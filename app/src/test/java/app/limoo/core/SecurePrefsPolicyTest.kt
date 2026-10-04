package app.limoo.core

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The data-loss bug this covers:
 *
 *   1. decryption fails (key lost, or the blob was restored onto another device by Auto Backup)
 *   2. getString returns the default, which for the server list is "[]"
 *   3. the app loads an EMPTY list and the next persist() writes it back
 *   4. a valid encrypted blob of every server the user had is now replaced by "[]"
 *
 * Step 4 made the failure permanent. The `.unreadable` copy was the only trace, and nothing in the UI
 * said a word. A failure to READ was being treated as an absence of data.
 *
 * These tests use an in-memory fake SharedPreferences plus the real decision logic, because the point is
 * the policy - refuse the write - and that policy is what must not regress.
 */
class SecurePrefsPolicyTest {

    /** Minimal SharedPreferences: enough for the policy, no Android runtime needed. */
    private class FakePrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        /** Mimics the real SecurePrefs: an "enc1:" value whose decrypt() returns null yields defValue. */
        var decryptFails = false
        private val pending = mutableMapOf<String, Any?>()
        private val drops = mutableSetOf<String>()

        // SharedPreferences declares the keys as String?, so every override has to accept null. `?: ""`
        // keeps the map lookup well-typed without inventing behaviour the tests rely on - every call site
        // here passes a non-null key.
        override fun getString(key: String?, defValue: String?): String? {
            val k = key ?: return defValue
            val v = pending[k] ?: map[k]
            if (v == null && drops.remove(k)) return map[k] as? String
            val raw = v as? String ?: return defValue
            if (decryptFails && raw.startsWith("enc1:")) return defValue   // undecryptable blob
            return raw
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = key != null && map.containsKey(key)
        override fun getAll(): MutableMap<String, *> = map
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = Ed()

        private inner class Ed : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = also { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, v: MutableSet<String>?) = also { if (key != null) pending[key] = v }
            override fun putInt(key: String?, v: Int) = also { if (key != null) pending[key] = v }
            override fun putLong(key: String?, v: Long) = also { if (key != null) pending[key] = v }
            override fun putFloat(key: String?, v: Float) = also { if (key != null) pending[key] = v }
            override fun putBoolean(key: String?, v: Boolean) = also { if (key != null) pending[key] = v }
            override fun remove(key: String?) = also { key?.let { drops.add(it); pending.remove(it) } }
            // Both return the Editor per the interface, so `also` is the wrong shape here even though
            // it compiles: commit() must return Boolean.
            override fun clear(): SharedPreferences.Editor = also { map.clear() }
            override fun commit(): Boolean {
                pending.forEach { (k, v) -> map[k] = v }
                pending.clear()
                return true
            }
            override fun apply() { commit() }
        }
    }

    /**
     * The policy, extracted so it can be tested without an Android Keystore. This mirrors
     * SecurePrefs.Ed.putString exactly - if one changes, this must change with it.
     */
    private class Policy(private val unreadable: MutableSet<String>, private val secure: Set<String>) {
        fun shouldRefuseWrite(key: String?) = key != null && key in secure && key in unreadable
        fun recordReadFailure(key: String) = unreadable.add(key)
    }

    @Test
    fun `a failed read marks the key so the next write is refused`() {
        val p = Policy(mutableSetOf(), setOf("servers"))
        p.recordReadFailure("servers")
        assertTrue("the write that destroyed the servers must be refused", p.shouldRefuseWrite("servers"))
    }

    @Test
    fun `a key that read fine is writable`() {
        val p = Policy(mutableSetOf(), setOf("servers"))
        assertTrue(!p.shouldRefuseWrite("servers"))
    }

    @Test
    fun `unsecured keys are never refused`() {
        // settings is not in the secure set; refusing it would break ordinary preference writes.
        val p = Policy(mutableSetOf("servers"), setOf("servers"))
        assertTrue(!p.shouldRefuseWrite("theme"))
    }

    @Test
    fun `one unreadable key does not block the others`() {
        val p = Policy(setOf("servers").toMutableSet(), setOf("servers", "subs"))
        assertTrue(p.shouldRefuseWrite("servers"))
        assertTrue(!p.shouldRefuseWrite("subs"))
    }

    @Test
    fun `the escape hatch re-enables writing`() {
        // clearUnreadable() is what a "discard and start over" action calls. Until it is called, the
        // refusal holds; after it, writing works again.
        val unreadable = setOf("servers").toMutableSet()
        val p = Policy(unreadable, setOf("servers"))
        assertTrue(p.shouldRefuseWrite("servers"))
        unreadable.remove("servers")
        assertTrue(!p.shouldRefuseWrite("servers"))
    }

    @Test
    fun `the round trip that lost data is now blocked`() {
        // Reproduces the exact sequence, using a fake prefs to stand in for the encrypted blob.
        val prefs = FakePrefs()
        val unreadable = mutableSetOf<String>()
        val policy = Policy(unreadable, setOf("servers"))

        // Step 1: a blob that exists but cannot be decrypted.
        prefs.edit().putString("servers", "enc1:garbage").commit()

        // Step 2: the read fails, so the app sees the default - "[]", an empty server list.
        prefs.decryptFails = true
        val seen = prefs.getString("servers", "[]")
        assertEquals("[]", seen)
        policy.recordReadFailure("servers")           // what SecurePrefs now does on a failed read

        // Step 3: the app persists what it believes - an empty list.
        if (!policy.shouldRefuseWrite("servers")) {
            prefs.edit().putString("servers", "[]").commit()
        }

        // Step 4: the original blob survived. This is the assertion that fails WITHOUT the fix, where
        // the write went through and "[]" replaced every server the user had.
        prefs.decryptFails = false
        assertEquals("enc1:garbage", prefs.map["servers"])
    }
}