package app.steady.data

import app.steady.detect.DetectorConfig
import app.steady.learning.Card
import app.steady.learning.FocusSession
import app.steady.rules.Energy
import app.steady.rules.Plan
import app.steady.rules.UnlockGrant
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

// ---- Contract interfaces (docs/CONTRACTS.md, "Repository interfaces"). Do not change signatures. ----

interface PlanRepository {
    /** Emits [EmptyPlan] until the user has saved a plan in Setup. */
    val plan: Flow<Plan>
    suspend fun save(plan: Plan)
}

interface SessionRepository {
    val sessions: Flow<List<FocusSession>>
    suspend fun add(s: FocusSession)
    suspend fun recordIntercept(surfaceId: String, at: Instant)
    fun interceptsOn(date: LocalDate): Flow<Int>
    /** Phase 12: intercepts per surface id for local days [from, toExclusive). Surfaces with none are absent. */
    fun interceptsBySurface(from: LocalDate, toExclusive: LocalDate): Flow<Map<String, Int>>
}

interface CardRepository {
    val cards: Flow<List<Card>>
    suspend fun upsert(cards: List<Card>)
    /** Phase 12: removes the cards with these ids (unknown ids are ignored). */
    suspend fun delete(ids: Collection<String>)
}

interface UnlockRepository {
    val grants: Flow<List<UnlockGrant>>
    suspend fun add(g: UnlockGrant)
    /** Returns a request id. Locally this only records a pending request; Phase 11 sends it to the backend. */
    suspend fun requestFromKeyHolder(surfaceId: String?, reason: String): String
}

data class Entitlement(val tier: Tier, val features: Set<String>)
enum class Tier { FREE, PLUS }

interface EntitlementRepository {
    val entitlement: Flow<Entitlement>
}

interface SyncApi {
    suspend fun pushPlan(plan: Plan): Plan
    suspend fun pullPlan(): Plan?
    suspend fun detectors(): DetectorConfig?
}

// ---- Phase 5 additions (not in the contract, safe for later phases to use). ----

/** Placeholder emitted before Setup is completed. Saved plans always have version >= 1. */
val EmptyPlan: Plan = Plan(version = 0, distractions = emptyList(), schedules = emptyList())

/** True once the user has saved a plan at least once. */
val Plan.isConfigured: Boolean get() = version > 0

/** A study task with a "tiny first step" shown on Today. Local only. */
data class StudyTask(
    val id: String,
    val title: String,
    val firstStep: String,
    val done: Boolean,
    val createdAt: Instant,
)

/** Today-screen data: tasks and the daily energy check-in. Local only, never synced. */
interface DayRepository {
    val tasks: Flow<List<StudyTask>>
    suspend fun addTask(title: String, firstStep: String): StudyTask
    suspend fun setTaskDone(id: String, done: Boolean)
    suspend fun deleteTask(id: String)
    fun energyOn(date: LocalDate): Flow<Energy?>
    suspend fun setEnergy(date: LocalDate, energy: Energy)
}
