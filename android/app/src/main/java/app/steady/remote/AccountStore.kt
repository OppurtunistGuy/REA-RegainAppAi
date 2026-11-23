package app.steady.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A signed-in backend account. [name] is what key holders see. */
data class Account(val userId: String, val token: String, val name: String?, val contact: String?)

/** Somebody whose key this user holds (remembered after accepting an invite). Local only. */
data class HeldKey(val ownerUserId: String, val ownerName: String)

/**
 * Account, API base URL and small sync bookkeeping. Nothing here is screen content.
 *
 * The bearer token is encrypted with an Android Keystore AES/GCM key ([KeystoreTokenCipher]) before it
 * is written to a private SharedPreferences file that is also excluded from backup.
 */
interface AccountStore {
    val account: StateFlow<Account?>
    val baseUrl: StateFlow<String>
    val heldKeys: StateFlow<List<HeldKey>>
    fun setBaseUrl(url: String)
    fun signIn(account: Account)
    /** Clears the token and everything derived from it (held keys, uploaded-session ids). */
    fun signOut()
    fun addHeldKey(key: HeldKey)
    fun uploadedSessionIds(): Set<String>
    fun markSessionsUploaded(ids: Collection<String>)

    companion object {
        /** The Android emulator's alias for the host machine's localhost (backend `npm run dev`). */
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8787"
        /** Uploaded-session ids kept for dedupe. The backend ignores duplicates anyway. */
        const val MAX_UPLOADED_IDS = 1000
    }
}

val AccountStore.isSignedIn: Boolean get() = account.value != null

class InMemoryAccountStore(
    initial: Account? = null,
    baseUrl: String = AccountStore.DEFAULT_BASE_URL,
) : AccountStore {
    private val _account = MutableStateFlow(initial)
    private val _baseUrl = MutableStateFlow(baseUrl)
    private val _held = MutableStateFlow<List<HeldKey>>(emptyList())
    private val uploaded = LinkedHashSet<String>()
    override val account: StateFlow<Account?> = _account.asStateFlow()
    override val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()
    override val heldKeys: StateFlow<List<HeldKey>> = _held.asStateFlow()
    override fun setBaseUrl(url: String) { _baseUrl.value = url }
    override fun signIn(account: Account) { _account.value = account }
    override fun signOut() {
        _account.value = null
        _held.value = emptyList()
        synchronized(uploaded) { uploaded.clear() }
    }
    override fun addHeldKey(key: HeldKey) {
        _held.value = _held.value.filterNot { it.ownerUserId == key.ownerUserId } + key
    }
    override fun uploadedSessionIds(): Set<String> = synchronized(uploaded) { uploaded.toSet() }
    override fun markSessionsUploaded(ids: Collection<String>) {
        synchronized(uploaded) {
            uploaded.addAll(ids)
            while (uploaded.size > AccountStore.MAX_UPLOADED_IDS) uploaded.remove(uploaded.first())
        }
    }
}

/**
 * SharedPreferences-backed store (file `steady_account`, private, not backed up). The token is stored
 * only as [TokenCipher] ciphertext; a plain token left by an older build is encrypted on first read.
 */
class PrefsAccountStore(
    private val prefs: SharedPreferences,
    private val cipher: TokenCipher,
    private val log: (String, Throwable?) -> Unit = { msg, e -> Log.w(TAG, msg, e) },
) : AccountStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE),
        KeystoreTokenCipher(),
    )

    private val _account = MutableStateFlow(readAccount())
    private val _baseUrl = MutableStateFlow(prefs.getString(K_BASE_URL, null) ?: AccountStore.DEFAULT_BASE_URL)
    private val _held = MutableStateFlow(readHeld())

    override val account: StateFlow<Account?> = _account.asStateFlow()
    override val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()
    override val heldKeys: StateFlow<List<HeldKey>> = _held.asStateFlow()

    private fun readAccount(): Account? {
        val userId = prefs.getString(K_USER, null) ?: return null
        val token = readToken() ?: return null
        return Account(userId, token, prefs.getString(K_NAME, null), prefs.getString(K_CONTACT, null))
    }

    /** Decrypts the stored token, migrating a legacy plain one. Unreadable → forget the account. */
    private fun readToken(): String? {
        prefs.getString(K_TOKEN_ENC, null)?.let { sealed ->
            val token = runCatching { cipher.decrypt(sealed) }.getOrNull()
            if (token == null) {
                log("Stored token could not be decrypted; signing out", null)
                clearAccountKeys()
            }
            return token
        }
        val legacy = prefs.getString(K_TOKEN, null) ?: return null
        try {
            val sealed = cipher.encrypt(legacy)
            prefs.edit {
                putString(K_TOKEN_ENC, sealed)
                remove(K_TOKEN)
            }
        } catch (e: Exception) {
            log("Could not encrypt the legacy token; keeping it for this run only", e)
            prefs.edit { remove(K_TOKEN) }
        }
        return legacy
    }

    private fun clearAccountKeys() {
        prefs.edit { listOf(K_TOKEN, K_TOKEN_ENC, K_USER, K_NAME, K_CONTACT, K_HELD, K_UPLOADED).forEach { remove(it) } }
    }

    private fun readHeld(): List<HeldKey> =
        prefs.getStringSet(K_HELD, emptySet()).orEmpty().mapNotNull { entry ->
            val i = entry.indexOf(SEP)
            if (i <= 0) null else HeldKey(entry.substring(0, i), entry.substring(i + 1))
        }.sortedBy { it.ownerName }

    override fun setBaseUrl(url: String) {
        prefs.edit { putString(K_BASE_URL, url) }
        _baseUrl.value = url
    }

    override fun signIn(account: Account) {
        val sealed = try {
            cipher.encrypt(account.token)
        } catch (e: Exception) {
            log("Could not encrypt the token; signed in for this run only", e)
            null
        }
        prefs.edit {
            remove(K_TOKEN)
            if (sealed != null) putString(K_TOKEN_ENC, sealed) else remove(K_TOKEN_ENC)
            putString(K_USER, account.userId)
            putString(K_NAME, account.name)
            putString(K_CONTACT, account.contact)
        }
        _account.value = account
    }

    override fun signOut() {
        clearAccountKeys()
        _account.value = null
        _held.value = emptyList()
    }

    override fun addHeldKey(key: HeldKey) {
        val next = _held.value.filterNot { it.ownerUserId == key.ownerUserId } + key
        prefs.edit { putStringSet(K_HELD, next.map { it.ownerUserId + SEP + it.ownerName }.toSet()) }
        _held.value = next.sortedBy { it.ownerName }
    }

    @Synchronized
    override fun uploadedSessionIds(): Set<String> = readUploaded().toSet()

    @Synchronized
    override fun markSessionsUploaded(ids: Collection<String>) {
        val list = (readUploaded() + ids).distinct().takeLast(AccountStore.MAX_UPLOADED_IDS)
        prefs.edit { putString(K_UPLOADED, list.joinToString(",")) }
    }

    private fun readUploaded(): List<String> =
        prefs.getString(K_UPLOADED, null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    private companion object {
        const val TAG = "SteadyAccount"
        const val FILE = "steady_account"
        /** Legacy plain token (Phase 11 builds); migrated to [K_TOKEN_ENC] and removed. */
        const val K_TOKEN = "token"
        const val K_TOKEN_ENC = "token_enc"
        const val K_USER = "user_id"
        const val K_NAME = "name"
        const val K_CONTACT = "contact"
        const val K_BASE_URL = "base_url"
        const val K_HELD = "held_keys"
        const val K_UPLOADED = "uploaded_sessions"
        const val SEP = '\u001F'
    }
}
