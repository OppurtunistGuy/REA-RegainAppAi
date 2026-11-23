package app.steady.data

import app.steady.data.db.CardDao
import app.steady.data.db.CardEntity
import app.steady.data.db.DayDao
import app.steady.data.db.EnergyCheckinEntity
import app.steady.data.db.InterceptEventEntity
import app.steady.data.db.PlanDao
import app.steady.data.db.PlanEntity
import app.steady.data.db.SessionDao
import app.steady.data.db.SessionEntity
import app.steady.data.db.SurfaceCount
import app.steady.data.db.TaskEntity
import app.steady.data.db.UnlockDao
import app.steady.data.db.UnlockGrantEntity
import app.steady.data.db.UnlockRequestEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** In-memory DAO fakes that mimic the Room queries in data/db/Daos.kt. */
class FakePlanDao : PlanDao {
    val row = MutableStateFlow<PlanEntity?>(null)
    override fun observe(): Flow<PlanEntity?> = row
    override suspend fun upsert(entity: PlanEntity) { row.value = entity }
}

class FakeSessionDao : SessionDao {
    val sessions = MutableStateFlow<Map<String, SessionEntity>>(emptyMap())
    val intercepts = MutableStateFlow<List<InterceptEventEntity>>(emptyList())
    override fun observeAll() = sessions.map { m -> m.values.sortedByDescending { it.startMs } }
    override suspend fun upsert(entity: SessionEntity) = sessions.update { it + (entity.id to entity) }
    override suspend fun insertIntercept(event: InterceptEventEntity) =
        intercepts.update { it + event.copy(id = it.size + 1L) }
    override fun countIntercepts(fromMs: Long, untilMs: Long) =
        intercepts.map { list -> list.count { it.atMs in fromMs until untilMs } }
    override fun countInterceptsBySurface(fromMs: Long, untilMs: Long) =
        intercepts.map { list ->
            list.filter { it.atMs in fromMs until untilMs }.groupingBy { it.surfaceId }.eachCount()
                .map { (id, n) -> SurfaceCount(id, n) }
        }
}

class FakeCardDao : CardDao {
    val cards = MutableStateFlow<Map<String, CardEntity>>(emptyMap())
    override fun observeAll() = cards.map { m -> m.values.sortedByDescending { it.createdAtMs } }
    override suspend fun upsert(cards: List<CardEntity>) = this.cards.update { m -> m + cards.associateBy { it.id } }
    val deleteCalls = mutableListOf<List<String>>()
    override suspend fun delete(ids: List<String>) {
        deleteCalls += ids
        cards.update { m -> m - ids.toSet() }
    }
}

class FakeUnlockDao : UnlockDao {
    val grants = MutableStateFlow<Map<String, UnlockGrantEntity>>(emptyMap())
    val requests = MutableStateFlow<List<UnlockRequestEntity>>(emptyList())
    override fun observeGrants() = grants.map { m -> m.values.sortedByDescending { it.fromMs } }
    override suspend fun upsertGrant(entity: UnlockGrantEntity) = grants.update { it + (entity.id to entity) }
    override suspend fun insertRequest(entity: UnlockRequestEntity) = requests.update { it + entity }
}

class FakeDayDao : DayDao {
    val tasks = MutableStateFlow<Map<String, TaskEntity>>(emptyMap())
    val energy = MutableStateFlow<Map<String, EnergyCheckinEntity>>(emptyMap())
    override fun observeTasks() = tasks.map { m -> m.values.sortedWith(compareBy({ it.done }, { it.createdAtMs })) }
    override suspend fun upsertTask(entity: TaskEntity) = tasks.update { it + (entity.id to entity) }
    override suspend fun setDone(id: String, done: Boolean) =
        tasks.update { m -> m[id]?.let { m + (id to it.copy(done = done)) } ?: m }
    override suspend fun deleteTask(id: String) = tasks.update { it - id }
    override fun observeEnergy(date: String) = energy.map { it[date] }
    override suspend fun upsertEnergy(entity: EnergyCheckinEntity) = energy.update { it + (entity.date to entity) }
}
