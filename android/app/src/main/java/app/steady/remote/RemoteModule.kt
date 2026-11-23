package app.steady.remote

import android.content.Context
import app.steady.data.DetectorConfigStore
import app.steady.data.PlanRepository
import app.steady.data.SessionRepository
import app.steady.data.UnlockRepository
import app.steady.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Everything Phase 11 adds, built once by `DefaultAppContainer`. Screens reach it through
 * [AppContainer.remote] (null for test containers, which then behave as signed out).
 */
class RemoteModule(
    val account: AccountStore,
    val api: ApiClient,
    val billing: BillingGateway,
    planRepo: PlanRepository,
    sessionRepo: SessionRepository,
    localUnlocks: UnlockRepository,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Must be the guard wired into [api]'s `onUnauthorized` (see [create]). */
    val authGuard: AuthGuard = AuthGuard(account),
    /** Refreshed from `GET /v1/detectors` after each successful sync. Null in tests that don't care. */
    private val detectors: DetectorConfigStore? = null,
) {
    val syncApi = RemoteSyncApi(api, account)
    val sync = SyncCoordinator(planRepo, syncApi, account)
    val entitlements = RemoteEntitlementRepository(billing, api, account, scope)
    val unlocks = RemoteUnlockRepository(
        local = localUnlocks,
        api = api,
        account = account,
        scope = scope,
        keyHolderId = { planRepo.plan.first().keyHolder?.id },
    )
    val uploader = SessionUploader(sessionRepo, api, account)

    private var started = false

    /** Starts sync, session upload and entitlement refresh. Idempotent. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        sync.start(scope)
        uploader.start(scope)
        entitlements.start()
        detectors?.let { store ->
            scope.launch {
                sync.status.filterIsInstance<SyncStatus.Synced>().collect { store.refresh(syncApi) }
            }
        }
    }

    // ---- account actions used by Settings ----

    /** Sends a one-time code to [contact] (phone number or email). */
    suspend fun startSignIn(contact: String) = api.startOtp(contact.trim())

    /** Verifies the code and stores the token. [name] is what a key holder sees. */
    suspend fun verifySignIn(contact: String, code: String, name: String?): Account {
        val auth = api.verifyOtp(contact.trim(), code.trim(), name?.trim()?.takeIf { it.isNotEmpty() })
        val acc = Account(auth.userId, auth.token, name?.trim()?.takeIf { it.isNotEmpty() }, contact.trim())
        account.signIn(acc)
        authGuard.clear()
        scope.launch { entitlements.refreshServer() }
        return acc
    }

    /**
     * Forgets the token right away, then revokes it on the server (`POST /v1/auth/logout`) in the
     * background; offline the token simply expires. Synced data stays on the server.
     */
    fun signOut() {
        val token = account.account.value?.token
        account.signOut()
        scope.launch {
            if (token != null) {
                try {
                    api.logout(token)
                } catch (e: ApiException) {
                    // Offline or already invalid: nothing else to do.
                }
            }
            entitlements.refreshServer()
        }
    }

    /**
     * `DELETE /v1/me`: erases the plan, sessions, unlock requests, invites, key-holder links,
     * entitlements and tokens on the server, then signs out locally. Throws [ApiException] (and stays
     * signed in) if the server could not be reached, so the user can retry. Local data is kept.
     */
    suspend fun deleteSyncedData() {
        if (!account.isSignedIn) throw NotSignedIn()
        api.deleteMe()
        account.signOut()
        authGuard.clear()
        entitlements.refreshServer()
    }

    companion object {
        fun create(
            context: Context,
            planRepo: PlanRepository,
            sessionRepo: SessionRepository,
            localUnlocks: UnlockRepository,
            detectors: DetectorConfigStore? = null,
        ): RemoteModule {
            val account = PrefsAccountStore(context)
            val guard = AuthGuard(account)
            val api = ApiClient(
                baseUrl = { account.baseUrl.value },
                token = { account.account.value?.token },
                onUnauthorized = guard::onUnauthorized,
            )
            val billing = runCatching { PlayBillingGateway(context) }.getOrDefault(NoBillingGateway)
            return RemoteModule(account, api, billing, planRepo, sessionRepo, localUnlocks, authGuard = guard, detectors = detectors)
        }
    }
}

/** Implemented by `DefaultAppContainer`; lets screens find the [RemoteModule] without changing [AppContainer]. */
interface RemoteHost {
    val remote: RemoteModule
}

val AppContainer.remote: RemoteModule? get() = (this as? RemoteHost)?.remote
