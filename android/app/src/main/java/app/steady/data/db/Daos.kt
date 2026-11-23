package app.steady.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Query("SELECT * FROM plan WHERE id = 0")
    fun observe(): Flow<PlanEntity?>

    @Upsert
    suspend fun upsert(entity: PlanEntity)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startMs DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Upsert
    suspend fun upsert(entity: SessionEntity)

    @Insert
    suspend fun insertIntercept(event: InterceptEventEntity)

    /** Count of intercepts in [fromMs, untilMs). */
    @Query("SELECT COUNT(*) FROM intercept_events WHERE atMs >= :fromMs AND atMs < :untilMs")
    fun countIntercepts(fromMs: Long, untilMs: Long): Flow<Int>
}

@Dao
interface CardDao {
    @Query("SELECT * FROM cards ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<CardEntity>>

    @Upsert
    suspend fun upsert(cards: List<CardEntity>)
}

@Dao
interface UnlockDao {
    @Query("SELECT * FROM unlock_grants ORDER BY fromMs DESC")
    fun observeGrants(): Flow<List<UnlockGrantEntity>>

    @Upsert
    suspend fun upsertGrant(entity: UnlockGrantEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRequest(entity: UnlockRequestEntity)
}

@Dao
interface DayDao {
    @Query("SELECT * FROM tasks ORDER BY done ASC, createdAtMs ASC")
    fun observeTasks(): Flow<List<TaskEntity>>

    @Upsert
    suspend fun upsertTask(entity: TaskEntity)

    @Query("UPDATE tasks SET done = :done WHERE id = :id")
    suspend fun setDone(id: String, done: Boolean)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteTask(id: String)

    @Query("SELECT * FROM energy_checkins WHERE date = :date")
    fun observeEnergy(date: String): Flow<EnergyCheckinEntity?>

    @Upsert
    suspend fun upsertEnergy(entity: EnergyCheckinEntity)
}
