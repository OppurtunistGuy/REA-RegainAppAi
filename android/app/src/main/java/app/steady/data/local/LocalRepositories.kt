package app.steady.data.local

import app.steady.data.CardRepository
import app.steady.data.DayRepository
import app.steady.data.EmptyPlan
import app.steady.data.PlanRepository
import app.steady.data.SessionRepository
import app.steady.data.StudyTask
import app.steady.data.UnlockRepository
import app.steady.data.db.CardDao
import app.steady.data.db.CardEntity
import app.steady.data.db.DayDao
import app.steady.data.db.EnergyCheckinEntity
import app.steady.data.db.InterceptEventEntity
import app.steady.data.db.PlanDao
import app.steady.data.db.PlanEntity
import app.steady.data.db.SessionDao
import app.steady.data.db.SessionEntity
import app.steady.data.db.TaskEntity
import app.steady.data.db.UnlockDao
import app.steady.data.db.UnlockGrantEntity
import app.steady.data.db.UnlockRequestEntity
import app.steady.learning.Card
import app.steady.learning.FocusSession
import app.steady.learning.ReviewState
import app.steady.rules.Energy
import app.steady.rules.Plan
import app.steady.rules.PlanCodec
import app.steady.rules.UnlockGrant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class LocalPlanRepository(private val dao: PlanDao, private val clock: Clock) : PlanRepository {
    override val plan: Flow<Plan> = dao.observe()
        .map { entity -> entity?.let { PlanCodec.decode(it.json) } ?: EmptyPlan }
        .distinctUntilChanged()

    /** Stores the plan as given. Callers bump [Plan.version] (Setup does `previous.version + 1`). */
    override suspend fun save(plan: Plan) {
        dao.upsert(PlanEntity(json = PlanCodec.encode(plan), version = plan.version, updatedAtMs = clock.millis()))
    }
}

/** [zone] is read per query, so day boundaries follow a device time-zone change. */
class LocalSessionRepository(private val dao: SessionDao, private val zone: () -> ZoneId) : SessionRepository {
    constructor(dao: SessionDao, zone: ZoneId) : this(dao, { zone })

    override val sessions: Flow<List<FocusSession>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    override suspend fun add(s: FocusSession) = dao.upsert(s.toEntity())

    override suspend fun recordIntercept(surfaceId: String, at: Instant) =
        dao.insertIntercept(InterceptEventEntity(surfaceId = surfaceId, atMs = at.toEpochMilli()))

    override fun interceptsOn(date: LocalDate): Flow<Int> {
        val z = zone()
        val from = date.atStartOfDay(z).toInstant().toEpochMilli()
        val until = date.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        return dao.countIntercepts(from, until)
    }

    override fun interceptsBySurface(from: LocalDate, toExclusive: LocalDate): Flow<Map<String, Int>> {
        val z = zone()
        val fromMs = from.atStartOfDay(z).toInstant().toEpochMilli()
        val untilMs = toExclusive.atStartOfDay(z).toInstant().toEpochMilli()
        return dao.countInterceptsBySurface(fromMs, untilMs).map { rows -> rows.associate { it.surfaceId to it.count } }
    }
}

class LocalCardRepository(private val dao: CardDao) : CardRepository {
    override val cards: Flow<List<Card>> = dao.observeAll().map { list -> list.map { it.toModel() } }
    override suspend fun upsert(cards: List<Card>) = dao.upsert(cards.map { it.toEntity() })
    override suspend fun delete(ids: Collection<String>) {
        // Chunked: SQLite caps bound parameters per statement.
        ids.distinct().chunked(DELETE_CHUNK).forEach { dao.delete(it) }
    }
}

private const val DELETE_CHUNK = 500

class LocalUnlockRepository(
    private val dao: UnlockDao,
    private val clock: Clock,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) : UnlockRepository {
    override val grants: Flow<List<UnlockGrant>> = dao.observeGrants().map { list -> list.map { it.toModel() } }

    override suspend fun add(g: UnlockGrant) = dao.upsertGrant(g.toEntity())

    override suspend fun requestFromKeyHolder(surfaceId: String?, reason: String): String {
        val id = idGen()
        dao.insertRequest(UnlockRequestEntity(id, surfaceId, reason, clock.millis()))
        return id
    }
}

class LocalDayRepository(
    private val dao: DayDao,
    private val clock: Clock,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) : DayRepository {
    override val tasks: Flow<List<StudyTask>> = dao.observeTasks().map { list -> list.map { it.toModel() } }

    override suspend fun addTask(title: String, firstStep: String): StudyTask {
        val task = StudyTask(idGen(), title.trim(), firstStep.trim(), done = false, createdAt = clock.instant())
        dao.upsertTask(task.toEntity())
        return task
    }

    override suspend fun setTaskDone(id: String, done: Boolean) = dao.setDone(id, done)
    override suspend fun deleteTask(id: String) = dao.deleteTask(id)

    override fun energyOn(date: LocalDate): Flow<Energy?> =
        dao.observeEnergy(date.toString()).map { e -> e?.let { runCatching { Energy.valueOf(it.energy) }.getOrNull() } }

    override suspend fun setEnergy(date: LocalDate, energy: Energy) =
        dao.upsertEnergy(EnergyCheckinEntity(date.toString(), energy.name))
}

// ---- mappers ----

internal fun SessionEntity.toModel() = FocusSession(
    id, scheduleId, Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs), intercepts, unlocks,
)

internal fun FocusSession.toEntity() = SessionEntity(
    id, scheduleId, start.toEpochMilli(), end.toEpochMilli(), intercepts, unlocks,
)

internal fun CardEntity.toModel() = Card(
    id = id, deckId = deckId, front = front, back = back, createdAt = Instant.ofEpochMilli(createdAtMs),
    state = ReviewState(Instant.ofEpochMilli(dueMs), stabilityDays, difficulty, reps, lapses),
)

internal fun Card.toEntity() = CardEntity(
    id = id, deckId = deckId, front = front, back = back, createdAtMs = createdAt.toEpochMilli(),
    dueMs = state.due.toEpochMilli(), stabilityDays = state.stabilityDays, difficulty = state.difficulty,
    reps = state.reps, lapses = state.lapses,
)

internal fun UnlockGrantEntity.toModel() =
    UnlockGrant(id, surfaceId, Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(untilMs), approvedBy)

internal fun UnlockGrant.toEntity() =
    UnlockGrantEntity(id, surfaceId, from.toEpochMilli(), until.toEpochMilli(), approvedBy)

internal fun TaskEntity.toModel() = StudyTask(id, title, firstStep, done, Instant.ofEpochMilli(createdAtMs))
internal fun StudyTask.toEntity() = TaskEntity(id, title, firstStep, done, createdAt.toEpochMilli())
