package app.steady.remote

import app.steady.data.UnlockRepository
import app.steady.rules.UnlockGrant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeParseException

/** Thrown by [RemoteUnlockRepository.requestFromKeyHolder] when there is no account: the key holder can't be reached. */
class NotSignedIn : IllegalStateException("Sign in to send unlock requests to your key holder")

/** The backend has no key holder linked to this account (409 `no_keyholder`): invite one first. */
class NoKeyHolderLinked : IllegalStateException("No key holder has accepted an invite yet")

sealed interface UnlockOutcome {
    data object Pending : UnlockOutcome
    data class Approved(val grant: UnlockGrant) : UnlockOutcome
    data object Denied : UnlockOutcome
    /** No decision within the polling window (15 min by default). */
    data object TimedOut : UnlockOutcome
    /** Polling stopped: signed out, or the request disappeared. */
    data object Abandoned : UnlockOutcome
}

/**
 * Decorates the local [UnlockRepository]: grants stay local, but [requestFromKeyHolder] goes to the
 * backend and is polled every [pollIntervalMs] for up to [timeoutMs]. On approval a grant with
 * `approvedBy = keyHolderId` is added through the local repository, so the rules engine sees it.
 * Progress per request id is exposed in [outcomes] for the UI.
 */
class RemoteUnlockRepository(
    private val local: UnlockRepository,
    private val api: ApiClient,
    private val account: AccountStore,
    private val scope: CoroutineScope,
    private val keyHolderId: suspend () -> String?,
    private val now: () -> Instant = Instant::now,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val timeoutMs: Long = TIMEOUT_MS,
) : UnlockRepository {
    override val grants: Flow<List<UnlockGrant>> get() = local.grants

    override suspend fun add(g: UnlockGrant) = local.add(g)

    private val _outcomes = MutableStateFlow<Map<String, UnlockOutcome>>(emptyMap())
    val outcomes: StateFlow<Map<String, UnlockOutcome>> = _outcomes.asStateFlow()

    /**
     * @throws NotSignedIn when signed out (the UI should explain that a key holder needs an account).
     * @throws NoKeyHolderLinked when nobody accepted an invite yet.
     * @throws ApiException on other failures (e.g. [ApiException.Network]).
     */
    override suspend fun requestFromKeyHolder(surfaceId: String?, reason: String): String {
        if (!account.isSignedIn) throw NotSignedIn()
        val created = try {
            api.createUnlockRequest(surfaceId, reason.trim())
        } catch (e: ApiException.Conflict) {
            if (e.code == "no_keyholder") throw NoKeyHolderLinked()
            throw e
        } catch (e: ApiException.Unauthorized) {
            throw NotSignedIn()
        }
        _outcomes.update { it + (created.id to UnlockOutcome.Pending) }
        scope.launch { awaitDecision(created.id, surfaceId) }
        return created.id
    }

    /** Polls until approved, denied, abandoned or timed out. Adds the grant on approval. */
    suspend fun awaitDecision(id: String, surfaceId: String?): UnlockOutcome {
        var waited = 0L
        var outcome: UnlockOutcome = UnlockOutcome.TimedOut
        while (waited < timeoutMs) {
            delay(pollIntervalMs)
            waited += pollIntervalMs
            val state = try {
                api.unlockRequest(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.Network) {
                continue
            } catch (e: ApiException.Server) {
                continue
            } catch (e: ApiException) {
                outcome = UnlockOutcome.Abandoned
                break
            }
            when (state.status) {
                UnlockStatus.PENDING -> continue
                UnlockStatus.DENIED -> { outcome = UnlockOutcome.Denied; break }
                UnlockStatus.APPROVED -> {
                    val grant = grantFor(id, surfaceId, state.until)
                    if (grant == null) { outcome = UnlockOutcome.Abandoned; break }
                    local.add(grant)
                    outcome = UnlockOutcome.Approved(grant)
                    break
                }
            }
        }
        _outcomes.update { it + (id to outcome) }
        return outcome
    }

    private suspend fun grantFor(id: String, surfaceId: String?, until: String?): UnlockGrant? {
        val end = try {
            until?.let(Instant::parse)
        } catch (e: DateTimeParseException) {
            null
        } ?: return null
        return UnlockGrant(
            id = "kh-$id",
            surfaceId = surfaceId,
            from = now(),
            until = end,
            approvedBy = keyHolderId() ?: KEY_HOLDER_FALLBACK_ID,
        )
    }

    companion object {
        const val POLL_INTERVAL_MS = 10_000L
        const val TIMEOUT_MS = 15 * 60_000L
        const val KEY_HOLDER_FALLBACK_ID = "keyholder"
    }
}
