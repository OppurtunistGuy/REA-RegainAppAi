package app.steady.learning

import java.time.Instant

/**
 * Picks the card for a 60-second swap (offered when an urge to scroll is caught).
 *
 * 1. If any card is due (`due <= now`): the most overdue one (earliest `due`; newest `createdAt` on ties).
 * 2. Otherwise: the newest card (latest `createdAt`).
 * 3. No cards: `null`.
 */
object SwapPicker {
    fun pick(cards: List<Card>, now: Instant): Card? {
        val due = cards.filter { !it.state.due.isAfter(now) }
        if (due.isNotEmpty()) {
            return due.minWithOrNull(compareBy<Card> { it.state.due }.thenByDescending { it.createdAt })
        }
        return cards.maxByOrNull { it.createdAt }
    }
}
