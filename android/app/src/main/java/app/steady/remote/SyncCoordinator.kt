package app.steady.remote

import app.steady.data.PlanRepository
import app.steady.data.SyncApi
import app.steady.data.isConfigured
import app.steady.rules.Plan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

sealed interface SyncStatus {
    data object SignedOut : SyncStatus
    /** Signed in, nothing synced yet in this app run. */
    data object Idle : SyncStatus
    data object Syncing : SyncStatus
    data class Synced(val at: Instant, val version: Int?) : SyncStatus
    data object Offline : SyncStatus
    /** The token was rejected; the account has been signed out. */
    data object SignInAgain : SyncStatus
    data class Failed(val reason: String) : SyncStatus
}

/**
 * Pure merge rule for one local and one server plan. The higher version wins; a tie goes to the
 * server (it may hold edits from the Chrome extension). Local edits are pushed only when the local
 * version is strictly higher, renumbered to `server + 1` because the backend accepts exactly that.
 */
object SyncMerge {
    sealed interface Decision {
        data object InSync : Decision
        data class UseServer(val plan: Plan) : Decision
        /** Push [plan] (already renumbered); on success it becomes the local plan too. */
        data class PushLocal(val plan: Plan) : Decision
    }

    fun decide(local: Plan, server: Plan?): Decision = when {
        server == null || server.version <= 0 ->
            if (local.isConfigured) Decision.PushLocal(local.copy(version = 1)) else Decision.InSync
        !local.isConfigured -> Decision.UseServer(server)
        local.version > server.version -> Decision.PushLocal(local.copy(version = server.version + 1))
        local.version < server.version -> Decision.UseServer(server)
        local == server -> Decision.InSync
        else -> Decision.UseServer(server)
    }
}

/**
 * Keeps the local plan and the server plan together. Runs on app start when signed in, after
 * sign-in, and [debounceMs] after every local plan change. Signed out it does nothing.
 */
class SyncCoordinator(
    private val planRepo: PlanRepository,
    private val sync: SyncApi,
    private val account: AccountStore,
    private val now: () -> Instant = Instant::now,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    private val _status = MutableStateFlow<SyncStatus>(if (account.isSignedIn) SyncStatus.Idle else SyncStatus.SignedOut)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val mutex = Mutex()
    /** The plan both sides agreed on last; local emissions equal to it need no sync. */
    @Volatile private var lastSynced: Plan? = null

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope): Job = scope.launch {
        combine(account.account.map { it?.userId }.distinctUntilChanged(), planRepo.plan) { uid, plan -> uid to plan }
            .debounce(debounceMs)
            .collect { (uid, plan) ->
                if (uid == null) {
                    lastSynced = null
                    _status.value = SyncStatus.SignedOut
                } else if (plan != lastSynced) {
                    syncOnce()
                }
            }
    }

    /** One full pull → merge → (push) round. Safe to call any time; calls are serialised. */
    suspend fun syncOnce(): SyncStatus = mutex.withLock {
        if (!account.isSignedIn) return@withLock SyncStatus.SignedOut.also { _status.value = it }
        _status.value = SyncStatus.Syncing
        val result = try {
            val local = planRepo.plan.first()
            val agreed = apply(local, SyncMerge.decide(local, sync.pullPlan()), retriesLeft = 1)
            lastSynced = agreed
            SyncStatus.Synced(now(), agreed?.version)
        } catch (e: ApiException.Unauthorized) {
            account.signOut()
            SyncStatus.SignInAgain
        } catch (e: ApiException.Network) {
            SyncStatus.Offline
        } catch (e: ApiException) {
            SyncStatus.Failed(e.message ?: "sync failed")
        } catch (e: ConflictLoop) {
            SyncStatus.Failed("conflict")
        }
        _status.value = result
        result
    }

    /** Returns the plan both sides now hold (null if neither has one). */
    private suspend fun apply(local: Plan, decision: SyncMerge.Decision, retriesLeft: Int): Plan? = when (decision) {
        SyncMerge.Decision.InSync -> local.takeIf { it.isConfigured }
        is SyncMerge.Decision.UseServer -> {
            planRepo.save(decision.plan)
            decision.plan
        }
        is SyncMerge.Decision.PushLocal -> {
            val sent = decision.plan
            val result = sync.pushPlan(sent)
            if (result == sent) {
                if (sent != local) planRepo.save(sent)
                sent
            } else {
                // 409: result is the server's current plan (version 0 = server has none).
                if (retriesLeft <= 0) throw ConflictLoop()
                apply(local, SyncMerge.decide(local, result.takeIf { it.version > 0 }), retriesLeft - 1)
            }
        }
    }

    private class ConflictLoop : Exception()

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 2_000L
    }
}
