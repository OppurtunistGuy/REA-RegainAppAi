package app.steady.learning

import app.steady.learning.serial.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/** One focus block. Aggregate data only (times and counts), never screen content. */
@Serializable
data class FocusSession(
    val id: String,
    val scheduleId: String?,
    @Serializable(with = InstantSerializer::class) val start: Instant,
    @Serializable(with = InstantSerializer::class) val end: Instant,
    val intercepts: Int,
    val unlocks: Int,
)
