package app.steady.learning

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeeklyGoalTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val monday = LocalDate.of(2026, 11, 23)
    private var n = 0

    private fun session(start: String, minutes: Long): FocusSession {
        val s = LocalDateTime.parse(start).atZone(zone).toInstant()
        return FocusSession("s${++n}", null, s, s.plusSeconds(minutes * 60), 0, 0)
    }

    @Test
    fun `days with enough focus count`() {
        val sessions = listOf(
            session("2026-11-23T09:00", 25),
            session("2026-11-24T09:00", 5),   // too short on its own
            session("2026-11-25T09:00", 4),
            session("2026-11-25T15:00", 6),   // 4 + 6 = 10 minutes total → counts
        )
        val p = WeeklyGoal(3, zone).progress(sessions, monday)
        assertEquals(setOf(monday, monday.plusDays(2)), p.doneDays)
        assertEquals(1, p.remainingDays)
        assertFalse(p.met)
        assertEquals(3, p.targetDays)
    }

    @Test
    fun `goal met and remaining never negative`() {
        val sessions = (0L..4L).map { session("2026-11-${23 + it}T08:00", 15) }
        val p = WeeklyGoal(3, zone).progress(sessions, monday)
        assertEquals(5, p.doneDays.size)
        assertEquals(0, p.remainingDays)
        assertTrue(p.met)
    }

    @Test
    fun `sessions outside the week are ignored`() {
        val sessions = listOf(
            session("2026-11-22T23:00", 30),  // Sunday before
            session("2026-11-30T00:10", 30),  // Monday after
            session("2026-11-29T23:50", 20),  // Sunday inside week
        )
        val p = WeeklyGoal(2, zone).progress(sessions, monday)
        assertEquals(setOf(LocalDate.of(2026, 11, 29)), p.doneDays)
    }

    @Test
    fun `session crossing midnight counts toward its start day`() {
        val p = WeeklyGoal(1, zone).progress(listOf(session("2026-11-24T23:55", 30)), monday)
        assertEquals(setOf(monday.plusDays(1)), p.doneDays)
        assertTrue(p.met)
    }

    @Test
    fun `local zone decides the day`() {
        // 23:30 UTC Monday is already Tuesday in Berlin.
        val s = java.time.Instant.parse("2026-11-23T23:30:00Z")
        val session = FocusSession("x", "sched", s, s.plusSeconds(1200), 2, 1)
        assertEquals(setOf(monday.plusDays(1)), WeeklyGoal(1, zone).progress(listOf(session), monday).doneDays)
        assertEquals(setOf(monday), WeeklyGoal(1, ZoneId.of("UTC")).progress(listOf(session), monday).doneDays)
    }

    @Test
    fun `custom minMinutes and inverted sessions`() {
        val s = session("2026-11-23T09:00", 10)
        val inverted = FocusSession("inv", null, s.end, s.start, 0, 0)
        assertTrue(WeeklyGoal(1, zone, minMinutes = 30).progress(listOf(s, inverted), monday).doneDays.isEmpty())
        assertEquals(1, WeeklyGoal(1, zone, minMinutes = 10).progress(listOf(s, inverted), monday).doneDays.size)
    }

    @Test
    fun `no sessions means nothing done and no penalty`() {
        val p = WeeklyGoal(4, zone).progress(emptyList(), monday)
        assertEquals(GoalProgress(4, emptySet(), 4, false), p)
    }

    @Test
    fun `weekStart must be a Monday and targets are validated`() {
        assertThrows<IllegalArgumentException> { WeeklyGoal(3, zone).progress(emptyList(), monday.plusDays(1)) }
        assertThrows<IllegalArgumentException> { WeeklyGoal(0, zone) }
        assertThrows<IllegalArgumentException> { WeeklyGoal(8, zone) }
        assertThrows<IllegalArgumentException> { WeeklyGoal(3, zone, minMinutes = -1) }
    }
}
