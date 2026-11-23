package app.steady.di

import android.content.Context
import app.steady.data.CardRepository
import app.steady.data.DayRepository
import app.steady.data.EntitlementRepository
import app.steady.data.FreeEntitlementRepository
import app.steady.data.NoopSyncApi
import app.steady.data.PlanRepository
import app.steady.data.SessionRepository
import app.steady.data.SyncApi
import app.steady.data.UnlockRepository
import app.steady.data.db.SteadyDatabase
import app.steady.data.local.LocalCardRepository
import app.steady.data.local.LocalDayRepository
import app.steady.data.local.LocalPlanRepository
import app.steady.data.local.LocalSessionRepository
import app.steady.data.local.LocalUnlockRepository
import app.steady.detect.DetectorCodec
import app.steady.detect.DetectorConfig
import app.steady.rules.RulesEngine
import java.time.Clock
import java.time.ZoneId

/**
 * Manual DI. Contract fields first (docs/CONTRACTS.md); Phase 5 additions after.
 * Later phases swap implementations inside [DefaultAppContainer] only.
 */
interface AppContainer {
    val planRepo: PlanRepository
    val sessionRepo: SessionRepository
    val cardRepo: CardRepository
    val unlockRepo: UnlockRepository
    val entitlements: EntitlementRepository
    val syncApi: SyncApi
    val rules: RulesEngine
    val detectorConfig: DetectorConfig

    // Phase 5 additions
    val dayRepo: DayRepository
    val clock: Clock
    val zone: ZoneId
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext
    private val db: SteadyDatabase by lazy { SteadyDatabase.build(appContext) }

    override val clock: Clock = Clock.systemDefaultZone()
    override val zone: ZoneId get() = ZoneId.systemDefault()

    override val planRepo: PlanRepository by lazy { LocalPlanRepository(db.planDao(), clock) }
    override val sessionRepo: SessionRepository by lazy { LocalSessionRepository(db.sessionDao(), zone) }
    override val cardRepo: CardRepository by lazy { LocalCardRepository(db.cardDao()) }
    override val unlockRepo: UnlockRepository by lazy { LocalUnlockRepository(db.unlockDao(), clock) }
    override val dayRepo: DayRepository by lazy { LocalDayRepository(db.dayDao(), clock) }

    // ---- Phase 11 swaps these two ----
    override val entitlements: EntitlementRepository = FreeEntitlementRepository()
    override val syncApi: SyncApi = NoopSyncApi

    override val rules: RulesEngine by lazy { RulesEngine(zone) }

    /** Seed config shipped in assets (copied from shared/detectors by the build). */
    override val detectorConfig: DetectorConfig by lazy {
        appContext.assets.open(DETECTOR_ASSET).bufferedReader().use { DetectorCodec.decode(it.readText()) }
    }

    companion object {
        const val DETECTOR_ASSET = "detectors/detectors.v1.json"
    }
}
