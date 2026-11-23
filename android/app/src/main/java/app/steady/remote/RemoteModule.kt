package app.steady.remote

import android.content.Context
import app.steady.data.PlanRepository
import app.steady.data.SessionRepository
import app.steady.data.UnlockRepository
import app.steady.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    }

    // ---- account actions used by Settings ----

    /** Sends a one-time code to [contact] (phone number or email). */
    suspend fun startSignIn(contact: String) = api.startOtp(contact.trim())

    /** Verifies the code and stores the token. [name] is what a key holder sees. */
    suspend fun verifySignIn(contact: String, code: String, name: String?): Account {
        val auth = api.verifyOtp(contact.trim(), code.trim(), name?.trim()?.takeIf { it.isNotEmpty() })
        val acc = Account(auth.userId, auth.token, name?.trim()?.takeIf { it.isNotEmpty() }, contact.trim())
        account.signIn(acc)
        scope.launch { entitlements.refreshServer() }
        return acc
    }

    /** Local sign-out: forgets the token. Synced data stays on the server (no DELETE /v1/me yet). */
    fun signOut() {
        account.signOut()
        scope.launch { entitlements.refreshServer() }
    }

    companion object {
        fun create(
            context: Context,
            planRepo: PlanRepository,
            sessionRepo: SessionRepository,
            localUnlocks: UnlockRepository,
        ): RemoteModule {
            val account = PrefsAccountStore(context)
            val api = ApiClient(baseUrl = { account.baseUrl.value }, token = { account.account.value?.token })
            val billing = runCatching { PlayBillingGateway(context) }.getOrDefault(NoBillingGateway)
            return RemoteModule(account, api, billing, planRepo, sessionRepo, localUnlocks)
        }
    }
}

/** Implemented by `DefaultAppContainer`; lets screens find the [RemoteModule] without changing [AppContainer]. */
interface RemoteHost {
    val remote: RemoteModule
}

val AppContainer.remote: RemoteModule? get() = (this as? RemoteHost)?.remote
