package app.steady.feature.partner

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.steady.R
import app.steady.data.PlanRepository
import app.steady.feature.settings.accountErrorText
import app.steady.remote.ApiException
import app.steady.remote.HeldKey
import app.steady.remote.Invite
import app.steady.remote.KeyHolderLink
import app.steady.remote.KeyHolderRequest
import app.steady.remote.OwnerLink
import app.steady.remote.RemoteModule
import app.steady.remote.WeeklySummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class PartnerMode { OWNER, HOLDER }

data class PartnerUiState(
    val mode: PartnerMode = PartnerMode.OWNER,
    val signedIn: Boolean = false,
    val myName: String? = null,
    val keyHolderName: String? = null,
    val invite: Invite? = null,
    val summary: WeeklySummary? = null,
    val codeInput: String = "",
    val heldKeys: List<HeldKey> = emptyList(),
    val requests: List<KeyHolderRequest> = emptyList(),
    val deciding: Set<String> = emptySet(),
    val busy: Boolean = false,
    @StringRes val error: Int? = null,
    val acceptedName: String? = null,
    /** Owner view (`GET /v1/keyholder/links`); null until loaded. */
    val holders: List<KeyHolderLink>? = null,
    /** Key-holder view (`GET /v1/keyholder/owners`); null until loaded. */
    val owners: List<OwnerLink>? = null,
    /** Weekly summary per owner user id (`GET /v1/keyholder/summary`). */
    val ownerSummaries: Map<String, WeeklySummary> = emptyMap(),
    /** Link waiting for "Remove?" confirmation. */
    val confirmRemove: PendingRemoval? = null,
    @StringRes val notice: Int? = null,
) {
    val canAccept: Boolean get() = !busy && codeInput.trim().length == 6
    /** 409 `keyholder_limit`: offer the Paywall next to the explanation. */
    val showPlusLink: Boolean get() = error == R.string.partner_err_keyholder_limit
}

/** A link the user tapped Remove on. [name] is the other person. */
data class PendingRemoval(val linkId: String, val name: String, val mode: PartnerMode)

/** Monday of the week containing [today] (ISO weeks, as the backend's weekly summary expects). */
fun weekStartOf(today: LocalDate): LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

class PartnerViewModel(
    private val remote: RemoteModule,
    private val planRepo: PlanRepository,
    private val zone: ZoneId,
    private val today: () -> LocalDate = { LocalDate.now(zone) },
) : ViewModel() {
    private val _state = MutableStateFlow(PartnerUiState())
    val state: StateFlow<PartnerUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            remote.account.account.collect { a ->
                _state.update { it.copy(signedIn = a != null, myName = a?.name) }
                if (a != null) refresh()
            }
        }
        viewModelScope.launch { remote.account.heldKeys.collect { h -> _state.update { it.copy(heldKeys = h) } } }
        viewModelScope.launch { planRepo.plan.collect { p -> _state.update { it.copy(keyHolderName = p.keyHolder?.name?.takeIf(String::isNotBlank)) } } }
    }

    fun setMode(m: PartnerMode) {
        _state.update { it.copy(mode = m, error = null) }
        refresh()
    }

    fun setCode(v: String) = _state.update {
        it.copy(codeInput = v.filter(Char::isLetterOrDigit).uppercase().take(6), error = null, acceptedName = null)
    }

    fun createInvite() = action { _state.update { it.copy(invite = remote.api.createInvite()) } }

    fun accept() = action {
        val r = remote.api.acceptInvite(_state.value.codeInput)
        remote.account.addHeldKey(HeldKey(r.ownerUserId, r.ownerName))
        _state.update { it.copy(codeInput = "", acceptedName = r.ownerName) }
        loadHolderView()
    }

    fun askRemove(linkId: String, name: String) =
        _state.update { it.copy(confirmRemove = PendingRemoval(linkId, name, it.mode), notice = null) }

    fun cancelRemove() = _state.update { it.copy(confirmRemove = null) }

    /** `DELETE /v1/keyholder/links/{id}` from either side, then reload that side's list. */
    fun confirmRemove() {
        val pending = _state.value.confirmRemove ?: return
        _state.update { it.copy(confirmRemove = null) }
        action {
            try {
                remote.api.removeKeyHolderLink(pending.linkId)
            } catch (e: ApiException.NotFound) {
                // Already removed (maybe by the other person): same result.
            }
            when (pending.mode) {
                PartnerMode.OWNER -> loadOwnerView()
                PartnerMode.HOLDER -> loadHolderView()
            }
            _state.update { it.copy(notice = R.string.partner_removed) }
        }
    }

    fun decide(id: String, approve: Boolean) {
        if (id in _state.value.deciding) return
        _state.update { it.copy(deciding = it.deciding + id, error = null) }
        viewModelScope.launch {
            try {
                remote.api.decide(id, approve, if (approve) APPROVE_MINUTES else null)
                _state.update { s -> s.copy(requests = s.requests.filterNot { it.id == id }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = accountErrorText(e)) }
                runCatching { loadRequests() }
            } finally {
                _state.update { it.copy(deciding = it.deciding - id) }
            }
        }
    }

    fun refresh() {
        if (!_state.value.signedIn) return
        action {
            when (_state.value.mode) {
                PartnerMode.OWNER -> loadOwnerView()
                PartnerMode.HOLDER -> loadHolderView()
            }
        }
    }

    private suspend fun loadOwnerView() {
        val holders = remote.api.keyHolderLinks()
        _state.update { it.copy(holders = holders) }
        val summary = remote.api.weeklySummary(weekStartOf(today()), zone)
        _state.update { it.copy(summary = summary) }
    }

    /** Owners whose key I hold, their weekly summaries, and their pending unlock requests. */
    private suspend fun loadHolderView() {
        val owners = remote.api.keyHolderOwners()
        _state.update { s -> s.copy(owners = owners, ownerSummaries = s.ownerSummaries.filterKeys { id -> owners.any { it.ownerUserId == id } }) }
        val week = weekStartOf(today())
        for (o in owners) {
            val summary = try {
                remote.api.ownerSummary(o.ownerUserId, week, zone)
            } catch (e: ApiException.NotFound) {
                null // link removed meanwhile
            }
            _state.update { s ->
                s.copy(ownerSummaries = if (summary == null) s.ownerSummaries - o.ownerUserId else s.ownerSummaries + (o.ownerUserId to summary))
            }
        }
        loadRequests()
    }

    private suspend fun loadRequests() {
        val list = remote.api.keyHolderRequests()
        _state.update { it.copy(requests = list) }
    }

    private fun action(block: suspend () -> Unit) {
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

    companion object {
        /** "Approve 15 min" (CONTRACTS: approval comes from the key holder, 15 minutes). */
        const val APPROVE_MINUTES = 15
    }
}
