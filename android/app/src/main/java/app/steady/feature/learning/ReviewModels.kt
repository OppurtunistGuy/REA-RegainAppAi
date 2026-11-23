package app.steady.feature.learning

import app.steady.learning.Card
import java.time.Instant
import java.time.ZoneId

/**
 * One review sitting. The queue is frozen when it starts (cards due at that moment), so rating a card
 * and getting a new list from the repository never reshuffles what the user is working through.
 */
data class ReviewSession(
    val queueIds: List<String>? = null,
    val position: Int = 0,
    val revealed: Boolean = false,
    /** Card being added or edited in the list below; null = no editor open. */
    val editor: CardDraft? = null,
    /** Card waiting for "Delete this card?" confirmation. */
    val confirmDeleteId: String? = null,
)

object ReviewQueue {
    /** Freezes the queue on the first card list (or after [restart]). */
    fun ensureStarted(s: ReviewSession, cards: List<Card>, now: Instant): ReviewSession =
        if (s.queueIds != null) s else s.copy(queueIds = ReviewFlow.dueQueue(cards, now).map { it.id }, position = 0, revealed = false)

    fun restart(s: ReviewSession): ReviewSession = s.copy(queueIds = null, position = 0, revealed = false)

    fun reveal(s: ReviewSession): ReviewSession = if (currentId(s) != null) s.copy(revealed = true) else s

    /** After a rating: next card, answer hidden. */
    fun advance(s: ReviewSession): ReviewSession {
        val q = s.queueIds ?: return s
        return s.copy(position = (s.position + 1).coerceAtMost(q.size), revealed = false)
    }

    fun currentId(s: ReviewSession): String? = s.queueIds?.getOrNull(s.position)

    /** Drops [id] from the frozen queue, keeping the user on the same next card. */
    fun remove(s: ReviewSession, id: String): ReviewSession {
        val q = s.queueIds ?: return s
        val index = q.indexOf(id)
        if (index < 0) return s
        val position = if (index < s.position) s.position - 1 else s.position
        return s.copy(queueIds = q - id, position = position, revealed = s.revealed && index != s.position)
    }
}

data class ReviewUiState(
    val loading: Boolean = true,
    val current: Card? = null,
    /** 1-based position for "4 of 9". */
    val position: Int = 0,
    val total: Int = 0,
    val revealed: Boolean = false,
    /** "tomorrow, 4 pm" for the done state; null when there are no cards at all. */
    val nextDue: String? = null,
    val cards: List<Card> = emptyList(),
    val dueLabels: Map<String, String> = emptyMap(),
    val editor: CardDraft? = null,
    val confirmDelete: Card? = null,
) {
    val done: Boolean get() = !loading && current == null
    val hadQueue: Boolean get() = total > 0
}

object ReviewStateBuilder {
    fun build(cards: List<Card>?, s: ReviewSession, now: Instant, zone: ZoneId): ReviewUiState {
        if (cards == null) return ReviewUiState(loading = true, editor = s.editor)
        val byId = cards.associateBy { it.id }
        val queue = s.queueIds.orEmpty()
        val currentId = ReviewQueue.currentId(s)
        val current = currentId?.let { byId[it] }
        val nextDue = cards.minByOrNull { it.state.due }?.let { c ->
            // A card that is already due counts as "now".
            DueLabels.relative(c.state.due, now, zone)
        }
        return ReviewUiState(
            loading = false,
            current = current,
            position = if (current != null) s.position + 1 else queue.size,
            total = queue.size,
            revealed = s.revealed && current != null,
            nextDue = nextDue,
            cards = cards.sortedByDescending { it.createdAt },
            dueLabels = cards.associate { it.id to DueLabels.relative(it.state.due, now, zone) },
            editor = s.editor,
            confirmDelete = s.confirmDeleteId?.let { byId[it] },
        )
    }
}
