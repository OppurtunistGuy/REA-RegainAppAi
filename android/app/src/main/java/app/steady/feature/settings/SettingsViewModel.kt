package app.steady.feature.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.steady.R
import app.steady.data.Entitlement
import app.steady.remote.Account
import app.steady.remote.ApiClient
import app.steady.remote.EntitlementLogic
import app.steady.remote.OwnedPurchase
import app.steady.remote.RemoteModule
import app.steady.remote.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OtpStep { CONTACT, CODE }

data class SettingsUiState(
    val account: Account? = null,
    val step: OtpStep = OtpStep.CONTACT,
    val contact: String = "",
    val code: String = "",
    val name: String = "",
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
    @StringRes val notice: Int? = null,
    val baseUrl: String = "",
    val savedBaseUrl: String = "",
    val sync: SyncStatus = SyncStatus.SignedOut,
    val entitlement: Entitlement = EntitlementLogic.FREE,
    val activePurchase: OwnedPurchase? = null,
    val confirmDelete: Boolean = false,
) {
    val canSendCode: Boolean get() = !busy && contact.trim().length >= 3
    val canVerify: Boolean get() = !busy && code.trim().length == 6 && code.trim().all { it.isDigit() }
}

class SettingsViewModel(private val remote: RemoteModule) : ViewModel() {
    private val _state = MutableStateFlow(
        SettingsUiState(baseUrl = remote.account.baseUrl.value, savedBaseUrl = remote.account.baseUrl.value),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { remote.account.account.collect { a -> _state.update { it.copy(account = a) } } }
        viewModelScope.launch { remote.sync.status.collect { s -> _state.update { it.copy(sync = s) } } }
        viewModelScope.launch { remote.entitlements.entitlement.collect { e -> _state.update { it.copy(entitlement = e) } } }
        viewModelScope.launch { remote.entitlements.activePurchase.collect { p -> _state.update { it.copy(activePurchase = p) } } }
        viewModelScope.launch { remote.account.baseUrl.collect { u -> _state.update { it.copy(savedBaseUrl = u) } } }
    }

    fun setContact(v: String) = _state.update { it.copy(contact = v, error = null) }
    fun setCode(v: String) = _state.update { it.copy(code = v.filter(Char::isDigit).take(6), error = null) }
    fun setName(v: String) = _state.update { it.copy(name = v.take(60), error = null) }
    fun setBaseUrl(v: String) = _state.update { it.copy(baseUrl = v, error = null, notice = null) }

    fun sendCode() = action {
        remote.startSignIn(_state.value.contact)
        _state.update { it.copy(step = OtpStep.CODE, code = "") }
    }

    fun verify() = action {
        val s = _state.value
        remote.verifySignIn(s.contact, s.code, s.name)
        _state.update { it.copy(step = OtpStep.CONTACT, code = "", notice = null) }
    }

    fun changeContact() = _state.update { it.copy(step = OtpStep.CONTACT, code = "", error = null) }

    fun signOut() {
        remote.signOut()
        _state.update { it.copy(step = OtpStep.CONTACT, code = "") }
    }

    fun saveBaseUrl() {
        val url = _state.value.baseUrl.trim()
        if (!ApiClient.isValidBaseUrl(url)) {
            _state.update { it.copy(error = R.string.settings_server_invalid) }
            return
        }
        remote.account.setBaseUrl(url.trimEnd('/'))
        _state.update { it.copy(baseUrl = url.trimEnd('/'), notice = R.string.settings_server_saved, error = null) }
    }

    fun syncNow() {
        viewModelScope.launch { remote.sync.syncOnce() }
    }

    fun askDelete(show: Boolean) = _state.update { it.copy(confirmDelete = show) }

    /**
     * There is no `DELETE /v1/me` yet, so this is a local sign-out that forgets the token.
     * TODO(backend): call DELETE /v1/me (plan, sessions, unlock requests, key holder links) first.
     */
    fun deleteSyncedData() {
        remote.signOut()
        _state.update { it.copy(confirmDelete = false, step = OtpStep.CONTACT, notice = R.string.settings_delete_done) }
    }

    private fun action(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = accountErrorText(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
