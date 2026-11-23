package app.steady.di

import android.content.Context
import app.steady.data.CardRepository
import app.steady.data.DetectorConfigStore
import app.steady.data.FileDetectorConfigPersistence
import app.steady.data.GuardedPlanRepository
import app.steady.data.DayRepository
import app.steady.data.EntitlementRepository
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
import app.steady.remote.RemoteHost
import app.steady.remote.RemoteModule
import app.steady.rules.RulesEngine
import java.io.File
import java.time.Clock
import java.time.Instant
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

/**
 * A system clock whose zone follows the device setting. `Clock.systemDefaultZone()` captures the
 * zone once; this one re-reads it, so a time-zone change is picked up without restarting.
 */
object DeviceClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = Clock.system(zone)
    override fun instant(): Instant = Instant.now()
    override fun millis(): Long = System.currentTimeMillis()
}

/**
 * [RulesEngine] for the current device zone: rebuilt the first time it is read after the zone
 * changes (TIMEZONE_CHANGED resets the JVM default zone in the app process), cached otherwise.
 */
class ZoneAwareRules(private val currentZone: () -> ZoneId = ZoneId::systemDefault) {
    @Volatile private var cached: Pair<ZoneId, RulesEngine>? = null

    fun get(): RulesEngine {
        val zone = currentZone()
        cached?.let { (z, engine) -> if (z == zone) return engine }
        return RulesEngine(zone).also { cached = zone to it }
    }
}

class DefaultAppContainer(context: Context) : AppContainer, RemoteHost {
    private val appContext = context.applicationContext
    private val db: SteadyDatabase by lazy { SteadyDatabase.build(appContext) }

    override val clock: Clock = DeviceClock
    override val zone: ZoneId get() = ZoneId.systemDefault()

    /** Every save goes through PlanGuard: no weakening the plan while a strict block runs. */
    override val planRepo: PlanRepository by lazy {
        GuardedPlanRepository(LocalPlanRepository(db.planDao(), clock), { rules }, clock)
    }
    override val sessionRepo: SessionRepository by lazy { LocalSessionRepository(db.sessionDao()) { zone } }
    override val cardRepo: CardRepository by lazy { LocalCardRepository(db.cardDao()) }
    private val localUnlockRepo: UnlockRepository by lazy { LocalUnlockRepository(db.unlockDao(), clock) }
    override val dayRepo: DayRepository by lazy { LocalDayRepository(db.dayDao(), clock) }

    // ---- Phase 11: remote-backed, local-first (signed out = local only, free tier) ----
    /** Seed config shipped in assets (copied from shared/detectors by the build), replaced by newer remote ones. */
    val detectors: DetectorConfigStore by lazy {
        DetectorConfigStore(
            seed = { appContext.assets.open(DETECTOR_ASSET).bufferedReader().use { DetectorCodec.decode(it.readText()) } },
            persistence = FileDetectorConfigPersistence(File(appContext.filesDir, DETECTOR_FILE)),
        )
    }

    override val remote: RemoteModule by lazy {
        RemoteModule.create(appContext, planRepo, sessionRepo, localUnlockRepo, detectors)
    }
    override val unlockRepo: UnlockRepository get() = remote.unlocks
    override val entitlements: EntitlementRepository get() = remote.entitlements
    override val syncApi: SyncApi get() = remote.syncApi

    private val zoneRules = ZoneAwareRules { zone }
    override val rules: RulesEngine get() = zoneRules.get()

    /** Always the newest known config; the accessibility service re-reads it on every event. */
    override val detectorConfig: DetectorConfig get() = detectors.current.value

    init {
        // Sync on start (when signed in), session upload and entitlement refresh run in the
        // module's own background scope; the Room DB is opened lazily off the main thread.
        remote.start()
    }

    companion object {
        const val DETECTOR_ASSET = "detectors/detectors.v1.json"
        const val DETECTOR_FILE = "detectors.remote.json"
    }
}
