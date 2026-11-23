package app.steady.learning

import app.steady.learning.serial.InstantSerializer
import kotlinx.serialization.Serializable
import java.time.Instant

/** A flashcard. [state] holds its spaced-review schedule (see [Scheduler]). */
@Serializable
data class Card(
    val id: String,
    val deckId: String,
    val front: String,
    val back: String,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    val state: ReviewState,
)

/**
 * Review schedule of a card.
 *
 * @property due when the card should next be shown.
 * @property stabilityDays memory stability in days; 0 means the card has never been recalled successfully.
 * @property difficulty 1 (easy) .. 10 (hard).
 * @property reps total number of reviews.
 * @property lapses number of times the card was forgotten ([Rating.AGAIN]).
 */
@Serializable
data class ReviewState(
    @Serializable(with = InstantSerializer::class) val due: Instant,
    val stabilityDays: Double,
    val difficulty: Double,
    val reps: Int,
    val lapses: Int,
)

/** How well the user remembered a card. */
enum class Rating { AGAIN, HARD, GOOD }
