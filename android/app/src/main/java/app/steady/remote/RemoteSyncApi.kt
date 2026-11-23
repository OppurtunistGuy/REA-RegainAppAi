package app.steady.remote

import app.steady.data.SyncApi
import app.steady.detect.DetectorConfig
import app.steady.rules.Plan

/**
 * [SyncApi] over the backend. Local-first: when signed out, [pullPlan] returns null and [pushPlan]
 * returns the plan unchanged, exactly like `NoopSyncApi`.
 *
 * [pushPlan] sends the plan as given (its version must be server + 1). On a 409 it returns the
 * server's current plan instead of throwing; callers compare the result with what they sent
 * (see [SyncCoordinator]). If the server has no plan at all, the conflict result is the sent plan
 * with version 0 so callers can retry with version 1.
 */
class RemoteSyncApi(
    private val api: ApiClient,
    private val account: AccountStore,
) : SyncApi {
    override suspend fun pushPlan(plan: Plan): Plan {
        if (!account.isSignedIn) return plan
        return try {
            api.putPlan(plan)
        } catch (e: ApiException.Conflict) {
            e.current ?: plan.copy(version = 0)
        }
    }

    override suspend fun pullPlan(): Plan? {
        if (!account.isSignedIn) return null
        return api.getPlan()
    }

    /** Detector config needs no account. Null when offline or the server has none. */
    override suspend fun detectors(): DetectorConfig? = try {
        api.detectors()
    } catch (e: ApiException) {
        null
    }
}
