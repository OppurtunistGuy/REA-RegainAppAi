// TODO(phase12): remove stubs source set once core modules merged.
// Minimal stand-in for core/learning (Phase 4): only the data types the app shell stores.
package app.steady.learning

import app.steady.rules.serial.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable data class Card(
    val id: String,
    val deckId: String,
    val front: String,
    val back: String,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    val state: ReviewState,
)

@Serializable data class ReviewState(
    @Serializable(with = InstantSerializer::class) val due: Instant,
    val stabilityDays: Double,
    val difficulty: Double,
    val reps: Int,
    val lapses: Int,
)

@Serializable data class FocusSession(
    val id: String,
    val scheduleId: String?,
    @Serializable(with = InstantSerializer::class) val start: Instant,
    @Serializable(with = InstantSerializer::class) val end: Instant,
    val intercepts: Int,
    val unlocks: Int,
)
