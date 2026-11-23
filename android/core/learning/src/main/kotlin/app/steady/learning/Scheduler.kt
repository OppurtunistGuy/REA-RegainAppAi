package app.steady.learning

import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A small, deterministic, FSRS-inspired spaced-review scheduler.
 *
 * Notation: `S` = stability in days, `D` = difficulty (1..10), `S'`/`D'` = values after the review.
 * After a successful review the next interval equals the new stability (`interval = S'` days, roughly the
 * point where recall probability falls to the target retention), capped at [MAX_INTERVAL_DAYS].
 *
 * First successful recall (`S == 0`):
 * - GOOD: `S' = 1.0` day.
 * - HARD: `S' = 0.5` day.
 *
 * Later successful recalls (`S > 0`); the growth factor uses the difficulty *before* the review:
 * - GOOD: `S' = S * (1 + 2.0 * (11 - D) / 6)` — at the default `D = 5` this is x3, so 1 day -> 3 days.
 * - HARD: `S' = S * (1 + 0.5 * (11 - D) / 6)` — at `D = 5` this is x1.5; always at least x1.08,
 *   and always less than GOOD for the same state.
 *
 * Forgetting:
 * - AGAIN: `S' = 0` if never recalled, else `max(0.25, 0.3 * S)`; the card is due again in
 *   [RELEARN_MINUTES] minutes and `lapses` increments.
 *
 * Difficulty:
 * - GOOD: `D' = D + 0.2 * (5 - D)` (gentle mean reversion toward 5)
 * - HARD: `D' = D + 1`
 * - AGAIN: `D' = D + 2`
 * - always clamped to `1..10`.
 *
 * Every review increments `reps`. Stability is also capped at [MAX_INTERVAL_DAYS].
 */
object Scheduler {
    const val DEFAULT_DIFFICULTY = 5.0
    const val MIN_DIFFICULTY = 1.0
    const val MAX_DIFFICULTY = 10.0
    const val MAX_INTERVAL_DAYS = 365.0
    const val RELEARN_MINUTES = 10L
    const val FIRST_GOOD_DAYS = 1.0
    const val FIRST_HARD_DAYS = 0.5
    const val MIN_LAPSED_STABILITY = 0.25

    private const val SECONDS_PER_DAY = 86_400.0

    /** A fresh card: due immediately, no stability, default difficulty. */
    fun newState(now: Instant): ReviewState =
        ReviewState(due = now, stabilityDays = 0.0, difficulty = DEFAULT_DIFFICULTY, reps = 0, lapses = 0)

    /** Applies [rating] at [now] and returns the new schedule. See the object KDoc for the formulas. */
    fun review(state: ReviewState, rating: Rating, now: Instant): ReviewState {
        val s = state.stabilityDays
        val d = state.difficulty.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
        return when (rating) {
            Rating.AGAIN -> {
                val newS = if (s <= 0.0) 0.0 else max(MIN_LAPSED_STABILITY, 0.3 * s)
                state.copy(
                    due = now.plus(Duration.ofMinutes(RELEARN_MINUTES)),
                    stabilityDays = min(newS, MAX_INTERVAL_DAYS),
                    difficulty = clampD(d + 2.0),
                    reps = state.reps + 1,
                    lapses = state.lapses + 1,
                )
            }
            Rating.HARD -> success(
                state, now,
                newS = if (s <= 0.0) FIRST_HARD_DAYS else s * (1 + 0.5 * (11 - d) / 6),
                newD = clampD(d + 1.0),
            )
            Rating.GOOD -> success(
                state, now,
                newS = if (s <= 0.0) FIRST_GOOD_DAYS else s * (1 + 2.0 * (11 - d) / 6),
                newD = clampD(d + 0.2 * (5 - d)),
            )
        }
    }

    private fun success(state: ReviewState, now: Instant, newS: Double, newD: Double): ReviewState {
        val stability = min(newS, MAX_INTERVAL_DAYS)
        return state.copy(
            due = now.plusSeconds((stability * SECONDS_PER_DAY).roundToLong()),
            stabilityDays = stability,
            difficulty = newD,
            reps = state.reps + 1,
        )
    }

    private fun clampD(d: Double) = d.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
}
