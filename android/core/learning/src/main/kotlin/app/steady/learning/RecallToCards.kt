package app.steady.learning

import java.time.Instant

/**
 * Turns the recall lines a user writes after a focus block into review cards.
 *
 * Per line (trimmed):
 * - Blank lines and lines shorter than [MIN_LINE_LENGTH] characters are skipped.
 * - Definition patterns `A = B`, `A: B`, `A - B` (spaced dash; also `–` / `—`), `A is B`: the separator that
 *   appears **earliest** in the line splits it into front `A` and back `B` (both trimmed, both non-blank).
 *   So `"Parallel axis: I = I_cm + Md²"` gives front `"Parallel axis"`, back `"I = I_cm + Md²"`.
 * - Otherwise a cloze card: the longest word of at least [MIN_CLOZE_WORD] letters (the first one on ties)
 *   is replaced by [CLOZE] on the front, and the back is that word.
 * - A line with neither a pattern nor such a word produces no card.
 *
 * Unicode (subscripts, superscripts, accents) is kept as written.
 */
object RecallToCards {
    const val MIN_LINE_LENGTH = 4
    const val MIN_CLOZE_WORD = 5
    const val CLOZE = "_____"

    private val separators = listOf(
        Regex("="),
        Regex(":(?=\\s|$)"),
        Regex("\\s[-–—]\\s"),
        Regex("\\sis\\s", RegexOption.IGNORE_CASE),
    )
    private val word = Regex("\\p{L}+")

    fun fromRecall(lines: List<String>, deckId: String, now: Instant, idGen: () -> String): List<Card> =
        lines.mapNotNull { raw ->
            val line = raw.trim()
            if (line.length < MIN_LINE_LENGTH) return@mapNotNull null
            val (front, back) = split(line) ?: cloze(line) ?: return@mapNotNull null
            Card(id = idGen(), deckId = deckId, front = front, back = back, createdAt = now, state = Scheduler.newState(now))
        }

    /** Splits on the earliest definition separator, or null if none yields two non-blank sides. */
    internal fun split(line: String): Pair<String, String>? {
        val hit = separators.mapNotNull { it.find(line) }.minByOrNull { it.range.first } ?: return null
        val front = line.substring(0, hit.range.first).trim()
        val back = line.substring(hit.range.last + 1).trim()
        return if (front.isEmpty() || back.isEmpty()) null else front to back
    }

    /** Hides the longest word of at least [MIN_CLOZE_WORD] letters, or null if there is none. */
    internal fun cloze(line: String): Pair<String, String>? {
        var best: MatchResult? = null
        for (m in word.findAll(line)) {
            if (m.value.length >= MIN_CLOZE_WORD && (best == null || m.value.length > best.value.length)) best = m
        }
        val target = best ?: return null
        return line.replaceRange(target.range, CLOZE) to target.value
    }
}
