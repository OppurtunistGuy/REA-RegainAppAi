package app.steady.feature.learning

import app.steady.learning.Card
import app.steady.learning.Rating
import app.steady.learning.Scheduler
import app.steady.learning.SwapPicker
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The 60-second swap timer. Pure: everything derives from when the swap started. */
object SwapTimer {
    const val SECONDS = 60

    fun secondsLeft(startedAt: Instant, now: Instant): Int {
        val elapsed = Duration.between(startedAt, now).seconds.coerceAtLeast(0)
        return (SECONDS - elapsed).coerceIn(0, SECONDS.toLong()).toInt()
    }

    /** 1.0 at the start, 0.0 when time is up. */
    fun fraction(secondsLeft: Int): Float = secondsLeft.coerceIn(0, SECONDS) / SECONDS.toFloat()

    /** 60 -> "1:00", 42 -> "0:42", 0 -> "0:00". */
    fun label(secondsLeft: Int): String {
        val s = secondsLeft.coerceIn(0, SECONDS)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
}

enum class SwapPhase { QUESTION, ANSWER, RATED }

/**
 * Mutable bits of one swap. The card is locked the first time cards are available, so a re-emission of
 * the card list (after rating) never swaps the card under the user.
 */
data class SwapSession(
    val startedAt: Instant,
    val cardId: String? = null,
    val phase: SwapPhase = SwapPhase.QUESTION,
    val rating: Rating? = null,
    val showAddForm: Boolean = false,
    val draft: CardDraft = CardDraft(),
)

object SwapReducer {
    /** Locks the card chosen by [SwapPicker] if none is locked yet (or the locked one disappeared). */
    fun ensurePicked(s: SwapSession, cards: List<Card>, now: Instant): SwapSession {
        if (s.cardId != null && cards.any { it.id == s.cardId }) return s
        val picked = SwapPicker.pick(cards, now) ?: return if (s.cardId == null) s else s.copy(cardId = null)
        // The 60 seconds start when a card is actually on screen (e.g. after adding sample cards).
        val startedAt = if (s.cardId == null) maxOf(s.startedAt, now) else s.startedAt
        return s.copy(cardId = picked.id, startedAt = startedAt, phase = SwapPhase.QUESTION, rating = null, showAddForm = false)
    }

    fun reveal(s: SwapSession): SwapSession = if (s.phase == SwapPhase.QUESTION && s.cardId != null) s.copy(phase = SwapPhase.ANSWER) else s

    /** Only an answered card can be rated, and only once. */
    fun canRate(s: SwapSession): Boolean = s.phase == SwapPhase.ANSWER && s.cardId != null

    fun rated(s: SwapSession, rating: Rating): SwapSession = if (canRate(s)) s.copy(phase = SwapPhase.RATED, rating = rating) else s
}

data class SwapUiState(
    val loading: Boolean = true,
    val card: Card? = null,
    val phase: SwapPhase = SwapPhase.QUESTION,
    val rating: Rating? = null,
    val secondsLeft: Int = SwapTimer.SECONDS,
    /** "today’s", "tomorrow’s", "Thursday’s" … for the copy under the card. */
    val reviewDay: String = "tomorrow’s",
    /** After rating: when the card comes back ("tomorrow, 4:07 pm"). */
    val nextDueLabel: String? = null,
    /** Deck name for "Back to physics"; null = "Back to focus". */
    val deckName: String? = null,
    val showAddForm: Boolean = false,
    val draft: CardDraft = CardDraft(),
) {
    val isEmpty: Boolean get() = !loading && card == null
    val timeUp: Boolean get() = secondsLeft == 0
    val timerLabel: String get() = SwapTimer.label(secondsLeft)
    val timerFraction: Float get() = SwapTimer.fraction(secondsLeft)
}

object SwapStateBuilder {
    /**
     * [reviewDay]: before rating it previews the day a "Got it" would bring the card back; after rating
     * it is the card's real next due day.
     */
    fun build(cards: List<Card>?, session: SwapSession, now: Instant, zone: ZoneId): SwapUiState {
        if (cards == null) return SwapUiState(loading = true, secondsLeft = SwapTimer.secondsLeft(session.startedAt, now))
        val card = session.cardId?.let { id -> cards.firstOrNull { it.id == id } }
        val nextDue = when {
            card == null -> null
            session.phase == SwapPhase.RATED -> card.state.due
            else -> Scheduler.review(card.state, Rating.GOOD, now).due
        }
        return SwapUiState(
            loading = false,
            card = card,
            phase = if (card == null) SwapPhase.QUESTION else session.phase,
            rating = session.rating,
            secondsLeft = SwapTimer.secondsLeft(session.startedAt, now),
            reviewDay = nextDue?.let { DueLabels.reviewDay(it, now, zone) } ?: "tomorrow’s",
            nextDueLabel = nextDue?.takeIf { session.phase == SwapPhase.RATED }?.let { DueLabels.relative(it, now, zone) },
            deckName = Decks.displayName(card?.deckId),
            showAddForm = session.showAddForm,
            draft = session.draft,
        )
    }
}
