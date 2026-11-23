package app.steady.feature.learning

import app.steady.learning.Card
import app.steady.learning.Rating
import app.steady.learning.ReviewState
import app.steady.learning.Scheduler
import app.steady.ui.Format
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Deck ids. A deck is only a string on [Card.deckId]; there is no deck table. */
object Decks {
    const val PHYSICS = "physics"
    const val NOTES = "notes"

    /** "physics" -> "physics"; blank or the generic [NOTES] deck -> null. Used for "Back to physics". */
    fun displayName(deckId: String?): String? = deckId?.trim()?.takeIf { it.isNotEmpty() && it != NOTES }

    /** Deck for recall cards: the block's schedule label ("Coaching" -> "coaching"), else [NOTES]. */
    fun fromLabel(label: String?): String = label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: NOTES
}

/** Minute counts for stats: 48 -> "48m", 80 -> "1h 20m"; long form "1 h 20 min". */
object Minutes {
    fun short(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        return when {
            m < 60 -> "${m}m"
            m % 60 == 0 -> "${m / 60}h"
            else -> "${m / 60}h ${m % 60}m"
        }
    }

    fun long(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        return when {
            m < 60 -> "$m min"
            m % 60 == 0 -> "${m / 60} h"
            else -> "${m / 60} h ${m % 60} min"
        }
    }
}

/** Limits for user-typed card text. */
object CardLimits {
    const val MAX_SIDE = 200
}

/** Three built-in starter cards for the empty state ("Add sample physics cards"). */
object StarterCards {
    val samples: List<Pair<String, String>> = listOf(
        "Moment of inertia of a solid sphere about its diameter?" to "I = ⅖ MR²",
        "Parallel axis theorem?" to "I = I_cm + Md²",
        "Ring or disc of the same mass and radius: which has the bigger I?" to "The ring (I = MR² vs ½ MR²)",
    )

    fun physics(now: Instant, idGen: () -> String): List<Card> =
        samples.map { (front, back) ->
            Card(idGen(), Decks.PHYSICS, front, back, createdAt = now, state = Scheduler.newState(now))
        }
}

/** Front/back editor state for adding or editing a card by hand. [id] null = a new card. */
data class CardDraft(val id: String? = null, val front: String = "", val back: String = "", val deckId: String = Decks.NOTES) {
    val canSave: Boolean get() = front.isNotBlank() && back.isNotBlank()

    fun withFront(v: String) = copy(front = v.take(CardLimits.MAX_SIDE))
    fun withBack(v: String) = copy(back = v.take(CardLimits.MAX_SIDE))

    companion object {
        fun of(card: Card) = CardDraft(card.id, card.front, card.back, card.deckId)
    }
}

object CardEditing {
    /**
     * Turns a draft into the card to upsert. New drafts get a fresh card due now; edits keep the id,
     * deck, creation time and review schedule. Returns null when either side is blank.
     */
    fun apply(draft: CardDraft, existing: List<Card>, now: Instant, idGen: () -> String): Card? {
        if (!draft.canSave) return null
        val front = draft.front.trim()
        val back = draft.back.trim()
        val old = draft.id?.let { id -> existing.firstOrNull { it.id == id } }
        return old?.copy(front = front, back = back)
            ?: Card(idGen(), draft.deckId.ifBlank { Decks.NOTES }, front, back, now, Scheduler.newState(now))
    }
}

/** One review: rate a card and get it back with its new schedule. */
object ReviewFlow {
    fun rate(card: Card, rating: Rating, now: Instant): Card = card.copy(state = Scheduler.review(card.state, rating, now))

    /** Cards due at [now] (due <= now), most overdue first, newest first on ties. */
    fun dueQueue(cards: List<Card>, now: Instant): List<Card> =
        cards.filter { !it.state.due.isAfter(now) }
            .sortedWith(compareBy<Card> { it.state.due }.thenByDescending { it.createdAt })
}

/** The three rating buttons, in screen order. "Got it" maps to [Rating.GOOD]. */
val RatingOrder: List<Rating> = listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD)

/** Human labels for due times, in the user's zone. English, matching [Format]. */
object DueLabels {
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun weekdayShort(day: DayOfWeek): String = Format.dayName(day.value)

    fun weekdayFull(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    /**
     * Possessive day for "it counts toward Thursday’s review": "today’s", "tomorrow’s", or the weekday
     * ("Thursday’s") within the next 6 days, else "the 4 Dec".
     */
    fun reviewDay(due: Instant, now: Instant, zone: ZoneId): String {
        val today = now.atZone(zone).toLocalDate()
        val d = maxOf(due, now).atZone(zone).toLocalDate()
        val days = Duration.between(today.atStartOfDay(), d.atStartOfDay()).toDays()
        return when {
            days <= 0L -> "today’s"
            days == 1L -> "tomorrow’s"
            days <= 6L -> "${weekdayFull(d.dayOfWeek)}’s"
            else -> "the ${d.format(dateFmt)}"
        }
    }

    /** "now", "in 10 min", "today, 9 pm", "tomorrow, 4:30 pm", "Thu, 4 pm", "4 Dec". */
    fun relative(due: Instant, now: Instant, zone: ZoneId): String {
        if (!due.isAfter(now)) return "now"
        val until = Duration.between(now, due)
        if (until < Duration.ofHours(1)) return Format.countdown(until)
        val today = now.atZone(zone).toLocalDate()
        val z = due.atZone(zone)
        val days = Duration.between(today.atStartOfDay(), z.toLocalDate().atStartOfDay()).toDays()
        val time = Format.time(z.toLocalTime())
        return when {
            days == 0L -> "today, $time"
            days == 1L -> "tomorrow, $time"
            days <= 6L -> "${weekdayShort(z.dayOfWeek)}, $time"
            else -> z.toLocalDate().format(dateFmt)
        }
    }
}

/**
 * Projects when a fresh card will come back if the user keeps getting it right: the first two
 * "Got it" reviews according to [Scheduler]. Used for the recap pill ("Wed, Sat").
 */
object ReviewProjection {
    fun nextDueTimes(state: ReviewState, now: Instant, count: Int = 2): List<Instant> {
        val out = mutableListOf<Instant>()
        var s = state
        var at = maxOf(now, state.due)
        repeat(count) {
            s = Scheduler.review(s, Rating.GOOD, at)
            out += s.due
            at = s.due
        }
        return out
    }

    /** Distinct short weekdays of the projected reviews, in order: "Wed, Sat". Empty for no cards. */
    fun dayLabels(cards: List<Card>, now: Instant, zone: ZoneId, count: Int = 2): List<String> {
        val first = cards.firstOrNull() ?: return emptyList()
        return nextDueTimes(first.state, now, count)
            .map { DueLabels.weekdayShort(it.atZone(zone).dayOfWeek) }
            .distinct()
    }
}
