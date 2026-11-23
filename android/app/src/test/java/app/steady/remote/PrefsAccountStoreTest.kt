package app.steady.remote

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reversible stand-in for the Keystore cipher. */
class FakeTokenCipher(private val broken: Boolean = false) : TokenCipher {
    override fun encrypt(plain: String): String = "enc:" + plain.reversed()
    override fun decrypt(stored: String): String? =
        if (broken || !stored.startsWith("enc:")) null else stored.removePrefix("enc:").reversed()
}

/** Minimal in-memory SharedPreferences (commit/apply are immediate). */
class MemPrefs : SharedPreferences {
    val map = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = map[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in map
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toMutableSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) map.clear()
            removed.forEach { map.remove(it) }
            pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            return true
        }
        override fun apply() {
            commit()
        }
    }
}

class PrefsAccountStoreTest {
    private val noLog: (String, Throwable?) -> Unit = { _, _ -> }

    @Test fun tokenIsStoredOnlyEncrypted() {
        val prefs = MemPrefs()
        PrefsAccountStore(prefs, FakeTokenCipher(), noLog).signIn(TEST_ACCOUNT)
        assertFalse(prefs.contains("token"))
        assertEquals("enc:" + TEST_ACCOUNT.token.reversed(), prefs.getString("token_enc", null))
        assertFalse(prefs.map.values.any { it == TEST_ACCOUNT.token })
        // A new process reads it back.
        assertEquals(TEST_ACCOUNT, PrefsAccountStore(prefs, FakeTokenCipher(), noLog).account.value)
    }

    @Test fun legacyPlainTokenIsMigrated() {
        val prefs = MemPrefs()
        prefs.map["token"] = "plain-token-123"
        prefs.map["user_id"] = "u9"
        prefs.map["name"] = "Riya"
        val store = PrefsAccountStore(prefs, FakeTokenCipher(), noLog)
        assertEquals("plain-token-123", store.account.value?.token)
        assertFalse(prefs.contains("token"))
        assertTrue(prefs.contains("token_enc"))
        assertEquals("plain-token-123", PrefsAccountStore(prefs, FakeTokenCipher(), noLog).account.value?.token)
    }

    @Test fun undecryptableTokenSignsOut() {
        val prefs = MemPrefs()
        PrefsAccountStore(prefs, FakeTokenCipher(), noLog).signIn(TEST_ACCOUNT)
        val store = PrefsAccountStore(prefs, FakeTokenCipher(broken = true), noLog)
        assertNull(store.account.value)
        assertFalse(prefs.contains("token_enc"))
        assertFalse(prefs.contains("user_id"))
    }

    @Test fun signOutClearsEverything() {
        val prefs = MemPrefs()
        val store = PrefsAccountStore(prefs, FakeTokenCipher(), noLog)
        store.signIn(TEST_ACCOUNT)
        store.addHeldKey(HeldKey("o1", "Asha"))
        store.signOut()
        assertNull(store.account.value)
        assertFalse(prefs.contains("token_enc"))
        assertFalse(prefs.contains("held_keys"))
    }
}
