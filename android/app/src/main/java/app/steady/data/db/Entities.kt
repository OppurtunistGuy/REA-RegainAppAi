package app.steady.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Single-row table. [json] is exactly what `PlanCodec.encode` produces. */
@Entity(tableName = "plan")
data class PlanEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val json: String,
    val version: Int,
    val updatedAtMs: Long,
) {
    companion object { const val SINGLETON_ID = 0 }
}

/** Aggregate-only focus session (times and counts). */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val scheduleId: String?,
    val startMs: Long,
    val endMs: Long,
    val intercepts: Int,
    val unlocks: Int,
)

/**
 * One caught urge. Stores only which surface and when; never screen content.
 * v2 adds the (atMs, surfaceId) index used by the per-surface counts (see [SteadyDatabase.MIGRATION_1_2]).
 */
@Entity(tableName = "intercept_events", indices = [Index("atMs"), Index("atMs", "surfaceId")])
data class InterceptEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val surfaceId: String,
    val atMs: Long,
)

@Entity(tableName = "cards", indices = [Index("deckId"), Index("dueMs")])
data class CardEntity(
    @PrimaryKey val id: String,
    val deckId: String,
    val front: String,
    val back: String,
    val createdAtMs: Long,
    val dueMs: Long,
    val stabilityDays: Double,
    val difficulty: Double,
    val reps: Int,
    val lapses: Int,
)

@Entity(tableName = "unlock_grants")
data class UnlockGrantEntity(
    @PrimaryKey val id: String,
    val surfaceId: String?,
    val fromMs: Long,
    val untilMs: Long,
    val approvedBy: String?,
)

@Entity(tableName = "unlock_requests")
data class UnlockRequestEntity(
    @PrimaryKey val id: String,
    val surfaceId: String?,
    val reason: String,
    val createdAtMs: Long,
    val status: String = STATUS_PENDING,
) {
    companion object { const val STATUS_PENDING = "pending" }
}

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val firstStep: String,
    val done: Boolean,
    val createdAtMs: Long,
)

/** Energy check-in per local date ("yyyy-MM-dd"). */
@Entity(tableName = "energy_checkins")
data class EnergyCheckinEntity(
    @PrimaryKey val date: String,
    val energy: String,
)
