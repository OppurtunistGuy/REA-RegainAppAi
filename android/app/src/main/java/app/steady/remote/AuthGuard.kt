package app.steady.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why the app signed itself out. Shown once in Settings. */
enum class SignedOutReason {
    /** 401 `token_expired`: 90 days without using the account. */
    EXPIRED,
    /** Any other 401 on an authenticated call (revoked elsewhere, account deleted, unknown token). */
    REJECTED,
}

/**
 * Graceful handling of 401 on authenticated calls: the stored account is signed out locally (only if
 * it still holds the token that was rejected, so a fresh sign-in is never undone by a late reply) and
 * [reason] tells the UI why. Wire [onUnauthorized] into [ApiClient].
 */
class AuthGuard(private val account: AccountStore) {
    private val _reason = MutableStateFlow<SignedOutReason?>(null)
    val reason: StateFlow<SignedOutReason?> = _reason.asStateFlow()

    fun onUnauthorized(token: String, error: ApiException.Unauthorized) {
        if (account.account.value?.token != token) return
        account.signOut()
        _reason.value = if (error.isExpired) SignedOutReason.EXPIRED else SignedOutReason.REJECTED
    }

    /** The user has seen the message (or signed in again). */
    fun clear() {
        _reason.value = null
    }
}
