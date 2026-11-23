package app.steady.learning

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/**
 * Progress toward a flexible weekly goal ("focus on N days this week").
 *
 * @property targetDays days the user aimed for this week.
 * @property doneDays local dates in the week that counted.
 * @property remainingDays `max(0, targetDays - doneDays.size)`: how many more days are needed to meet the goal.
 * @property met `doneDays.size >= targetDays`.
 */
data class GoalProgress(val targetDays: Int, val doneDays: Set<LocalDate>, val remainingDays: Int, val met: Boolean)

/**
 * Flexible weekly goal. No streaks, ever: each week stands alone and missed days are never held against
 * the user.
 *
 * A local day (in [zone]) counts when the total focus time of sessions *starting* that day is at least
 * [minMinutes]. A session that crosses midnight counts entirely toward its start day. Sessions with
 * `end < start` contribute zero.
 */
class WeeklyGoal(private val targetDays: Int, private val zone: ZoneId, private val minMinutes: Int = 10) {
    init {
        require(targetDays in 1..7) { "targetDays must be 1..7, was $targetDays" }
        require(minMinutes >= 0) { "minMinutes must be >= 0, was $minMinutes" }
    }

    /** @param weekStart the Monday that starts the week (must be a Monday). */
    fun progress(sessions: List<FocusSession>, weekStart: LocalDate): GoalProgress {
        require(weekStart.dayOfWeek == DayOfWeek.MONDAY) { "weekStart must be a Monday, was $weekStart" }
        val weekEnd = weekStart.plusDays(6)
        val secondsByDay = sessions
            .groupBy { it.start.atZone(zone).toLocalDate() }
            .filterKeys { !it.isBefore(weekStart) && !it.isAfter(weekEnd) }
            .mapValues { (_, list) -> list.sumOf { Duration.between(it.start, it.end).seconds.coerceAtLeast(0) } }
        val done = secondsByDay.filterValues { it >= minMinutes * 60L }.keys.toSortedSet()
        return GoalProgress(
            targetDays = targetDays,
            doneDays = done,
            remainingDays = maxOf(0, targetDays - done.size),
            met = done.size >= targetDays,
        )
    }
}
