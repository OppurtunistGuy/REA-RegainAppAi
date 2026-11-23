package app.steady.feature.insights

import app.steady.feature.setup.SetupDefaults
import app.steady.learning.Card
import app.steady.learning.FocusSession
import app.steady.ui.Format
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** One week (Monday start, in the user's zone). */
data class WeekStat(val weekStart: LocalDate, val label: String, val focusMinutes: Int, val intercepts: Int, val isCurrent: Boolean)

data class SurfaceCount(val surfaceId: String, val label: String, val count: Int)

/** Cards due on one of the next 7 days; overdue cards count toward today. */
data class DueDay(val date: LocalDate, val label: String, val count: Int)

enum class Trend { UP, SAME, DOWN, NONE }

data class InsightsSummary(val thisWeekMinutes: Int, val thisWeekIntercepts: Int, val trend: Trend, val anyData: Boolean)

data class InsightsUiState(
    val loading: Boolean = true,
    val weeks: List<WeekStat> = emptyList(),
    val topSurfaces: List<SurfaceCount> = emptyList(),
    val dueDays: List<DueDay> = emptyList(),
    val summary: InsightsSummary = InsightsSummary(0, 0, Trend.NONE, anyData = false),
) {
    val dueNext7: Int get() = dueDays.sumOf { it.count }
}

object InsightsLogic {
    const val WEEKS = 4
    const val TOP_SURFACES = 3

    /** Within ±10 % (or ±10 min) of last week counts as "about the same". */
    private const val SAME_FRACTION = 0.10
    private const val SAME_MINUTES = 10

    private val weekFmt = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Mondays of the last [weeks] weeks, oldest first, ending with the current week. */
    fun weekStarts(today: LocalDate, weeks: Int = WEEKS): List<LocalDate> {
        val current = weekStart(today)
        return (weeks - 1 downTo 0).map { current.minusWeeks(it.toLong()) }
    }

    /** All local dates covered by [weekStarts], for querying intercepts per day. */
    fun days(today: LocalDate, weeks: Int = WEEKS): List<LocalDate> {
        val first = weekStarts(today, weeks).first()
        return (0 until weeks * 7).map { first.plusDays(it.toLong()) }
    }

    /** Friendly name for a surface id: known ones from Setup, else the package or domain. */
    fun surfaceLabel(id: String): String =
        SetupDefaults.distractions.firstOrNull { it.surfaceId == id }?.label
            ?: id.removePrefix("app:").removePrefix("site:")

    fun trend(thisWeek: Int, lastWeek: Int): Trend {
        if (thisWeek == 0 && lastWeek == 0) return Trend.NONE
        val diff = thisWeek - lastWeek
        val tolerance = maxOf(SAME_MINUTES.toDouble(), lastWeek * SAME_FRACTION)
        return when {
            kotlin.math.abs(diff) <= tolerance -> Trend.SAME
            diff > 0 -> Trend.UP
            else -> Trend.DOWN
        }
    }

    /**
     * Buckets everything by local week in [zone]. Sessions count toward the week of their start day
     * (as in WeeklyGoal); [interceptsByDay] is the count per local date.
     */
    fun build(
        sessions: List<FocusSession>,
        interceptsByDay: Map<LocalDate, Int>,
        surfaceCounts: Map<String, Int>,
        cards: List<Card>,
        now: Instant,
        zone: ZoneId,
    ): InsightsUiState {
        val today = now.atZone(zone).toLocalDate()
        val starts = weekStarts(today)
        val currentStart = starts.last()
        val secondsByWeek = sessions.groupBy { weekStart(it.start.atZone(zone).toLocalDate()) }
            .mapValues { (_, l) -> l.sumOf { Duration.between(it.start, it.end).seconds.coerceAtLeast(0) } }
        val interceptsByWeek = interceptsByDay.entries.groupBy({ weekStart(it.key) }, { it.value.coerceAtLeast(0) })
            .mapValues { it.value.sum() }

        val weeks = starts.map { ws ->
            WeekStat(
                weekStart = ws,
                label = ws.format(weekFmt),
                focusMinutes = ((secondsByWeek[ws] ?: 0L) / 60).toInt(),
                intercepts = interceptsByWeek[ws] ?: 0,
                isCurrent = ws == currentStart,
            )
        }

        val top = surfaceCounts.filterValues { it > 0 }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP_SURFACES)
            .map { SurfaceCount(it.key, surfaceLabel(it.key), it.value) }

        val dueDays = (0L..6L).map { i ->
            val d = today.plusDays(i)
            val end = d.plusDays(1).atStartOfDay(zone).toInstant()
            val start = d.atStartOfDay(zone).toInstant()
            val count = cards.count { c ->
                val due = c.state.due
                due.isBefore(end) && (i == 0L || !due.isBefore(start))
            }
            DueDay(d, Format.dayName(d.dayOfWeek.value), count)
        }

        val thisWeek = weeks.last()
        // Compare like with like: last week up to the same weekday as today, so a Monday never looks "lighter".
        val lastMonday = currentStart.minusWeeks(1)
        val lastWeekCutoff = lastMonday.plusDays(Duration.between(currentStart.atStartOfDay(), today.atStartOfDay()).toDays())
        val lastWeekSoFar = (sessions.filter {
            val d = it.start.atZone(zone).toLocalDate()
            !d.isBefore(lastMonday) && !d.isAfter(lastWeekCutoff)
        }.sumOf { Duration.between(it.start, it.end).seconds.coerceAtLeast(0) } / 60).toInt()
        val anyData = weeks.any { it.focusMinutes > 0 || it.intercepts > 0 }
        return InsightsUiState(
            loading = false,
            weeks = weeks,
            topSurfaces = top,
            dueDays = dueDays,
            summary = InsightsSummary(
                thisWeekMinutes = thisWeek.focusMinutes,
                thisWeekIntercepts = thisWeek.intercepts,
                trend = trend(thisWeek.focusMinutes, lastWeekSoFar),
                anyData = anyData,
            ),
        )
    }
}
