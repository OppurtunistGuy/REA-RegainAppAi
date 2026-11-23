package app.steady.data

import app.steady.detect.DetectorConfig
import app.steady.rules.Plan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Local-first default: nothing leaves the device. Phase 11 replaces this via AppContainer. */
object NoopSyncApi : SyncApi {
    override suspend fun pushPlan(plan: Plan): Plan = plan
    override suspend fun pullPlan(): Plan? = null
    override suspend fun detectors(): DetectorConfig? = null
}

object Features {
    const val UNLIMITED_SCHEDULES = "unlimited_schedules"
    const val DESKTOP_SYNC = "desktop_sync"
    const val NOTES_TO_CARDS = "notes_to_cards"
    const val TASK_BREAKDOWN = "task_breakdown"

    /** Free-tier limit on schedules (CONTRACTS: free = 2 schedules). */
    const val FREE_SCHEDULE_LIMIT = 2
}

/** Everyone gets the free tier until Phase 11 wires billing. */
class FreeEntitlementRepository : EntitlementRepository {
    override val entitlement: Flow<Entitlement> = flowOf(FREE)

    companion object {
        val FREE = Entitlement(Tier.FREE, emptySet())
    }
}
