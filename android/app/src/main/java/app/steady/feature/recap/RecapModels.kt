package app.steady.feature.recap

import app.steady.feature.learning.Decks
import app.steady.feature.learning.ReviewProjection
import app.steady.learning.Card
import app.steady.learning.FocusSession
import app.steady.learning.GoalProgress
import app.steady.learning.RecallToCards
import app.steady.learning.WeeklyGoal
import app.steady.rules.Plan
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** One square of the Mon–Sun week row. */
data class WeekCell(val date: LocalDate, val letter: String, val done: Boolean, val isToday: Boolean)

/** Result of turning recall lines into cards. [dayLabels] e.g. ["Wed", "Sat"]. */
data class SavedRecall(val count: Int, val dayLabels: List<String>)

data class RecapUiState(
    val loading: Boolean = true,
    /** Length of the block just finished, if one finished today. */
    val blockMinutes: Int? = null,
    val lines: List<String> = List(RecapLogic.LINES) { "" },
    val saved: SavedRecall? = null,
    val focusedMinutesToday: Int = 0,
    val urgesToday: Int = 0,
    val unlocksToday: Int = 0,
    val goalTarget: Int = RecapLogic.GOAL_DAYS,
    val week: List<WeekCell> = emptyList(),
    val goalRemaining: Int = RecapLogic.GOAL_DAYS,
    val goalMet: Boolean = false,
) {
    /** Saving is allowed once per recap, and only when a line has text. A save that made 0 cards can be retried. */
    val canSave: Boolean get() = lines.any { it.isNotBlank() } && (saved == null || saved.count == 0)
    val linesLocked: Boolean get() = (saved?.count ?: 0) > 0
}

object RecapLogic {
    const val LINES = 3
    const val GOAL_DAYS = 4
    const val MAX_LINE = 160
    private val letters = listOf("M", "T", "W", "T", "F", "S", "S")

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Mon–Sun cells for the week containing [today]; done days come from [WeeklyGoal]. */
    fun weekRow(sessions: List<FocusSession>, today: LocalDate, zone: ZoneId, targetDays: Int = GOAL_DAYS): Pair<List<WeekCell>, GoalProgress> {
        val monday = weekStart(today)
        val progress = WeeklyGoal(targetDays, zone).progress(sessions, monday)
        val cells = (0L..6L).map { i ->
            val d = monday.plusDays(i)
            WeekCell(d, letters[i.toInt()], d in progress.doneDays, d == today)
        }
        return cells to progress
    }

    /** Deck for recall cards: the label of the schedule of the latest block, else "notes". */
    fun deckFor(sessions: List<FocusSession>, plan: Plan): String {
        val scheduleId = sessions.maxByOrNull { it.end }?.scheduleId
        return Decks.fromLabel(plan.schedules.firstOrNull { it.id == scheduleId }?.label)
    }

    /** Lines -> cards (via [RecallToCards]) and the projected review days for the pill. */
    fun toCards(lines: List<String>, deckId: String, now: Instant, zone: ZoneId, idGen: () -> String): Pair<List<Card>, SavedRecall> {
        val cards = RecallToCards.fromRecall(lines, deckId, now, idGen)
        return cards to SavedRecall(cards.size, ReviewProjection.dayLabels(cards, now, zone))
    }

    fun setLine(lines: List<String>, index: Int, value: String): List<String> =
        if (index !in lines.indices) lines else lines.toMutableList().also { it[index] = value.replace('\n', ' ').take(MAX_LINE) }
}

object RecapStateBuilder {
    fun build(
        sessions: List<FocusSession>,
        interceptsToday: Int,
        now: Instant,
        zone: ZoneId,
        lines: List<String>,
        saved: SavedRecall?,
    ): RecapUiState {
        val today = now.atZone(zone).toLocalDate()
        val todays = sessions.filter { it.start.atZone(zone).toLocalDate() == today }
        val latest = sessions.filter { it.end.atZone(zone).toLocalDate() == today }.maxByOrNull { it.end }
        val (week, progress) = RecapLogic.weekRow(sessions, today, zone)
        return RecapUiState(
            loading = false,
            blockMinutes = latest?.let { minutes(it) },
            lines = lines,
            saved = saved,
            focusedMinutesToday = todays.sumOf { minutes(it) },
            urgesToday = interceptsToday,
            unlocksToday = todays.sumOf { it.unlocks.coerceAtLeast(0) },
            goalTarget = progress.targetDays,
            week = week,
            goalRemaining = progress.remainingDays,
            goalMet = progress.met,
        )
    }

    private fun minutes(s: FocusSession): Int = (Duration.between(s.start, s.end).seconds.coerceAtLeast(0) / 60).toInt()
}
