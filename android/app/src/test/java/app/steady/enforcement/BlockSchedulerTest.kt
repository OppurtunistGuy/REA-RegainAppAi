package app.steady.enforcement

import app.steady.data.EmptyPlan
import app.steady.rules.Plan
import app.steady.rules.RulesEngine
import app.steady.rules.Schedule
import app.steady.rules.Surface
import app.steady.rules.SurfaceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.ZoneId

class BlockSchedulerTest {
    private val zone = ZoneId.of("Europe/Berlin") // UTC+1 in November
    private val rules = RulesEngine(zone)
    private val reels = listOf(Surface("instagram.reels", SurfaceMode.HIDE_SECTION))

    private fun plan(vararg s: Schedule) = Plan(distractions = reels, schedules = s.toList())

    // Mon 2026-11-23
    private fun at(local: String) = java.time.LocalDateTime.parse(local).atZone(zone).toInstant()

    @Test fun idleWakesAtNextStart() {
        val p = plan(Schedule("s", "Coaching block", setOf(1, 2, 3, 4, 5), "16:00", "17:30"))
        val n = BlockScheduler.compute(rules, p, at("2026-11-23T09:00"))
        assertFalse(n.focusRunning)
        assertNull(n.scheduleId)
        assertEquals(at("2026-11-23T16:00"), n.wakeAt)
    }

    @Test fun activeRunsUntilEnd() {
        val p = plan(Schedule("s", "Coaching block", setOf(1), "16:00", "17:30"))
        val n = BlockScheduler.compute(rules, p, at("2026-11-23T16:00"))
        assertTrue(n.focusRunning)
        assertEquals("s", n.scheduleId)
        assertEquals(at("2026-11-23T17:30"), n.wakeAt)
    }

    @Test fun atEndIsIdleAndWaitsForNextWeek() {
        val p = plan(Schedule("s", "Coaching block", setOf(1), "16:00", "17:30"))
        val n = BlockScheduler.compute(rules, p, at("2026-11-23T17:30"))
        assertFalse(n.focusRunning)
        assertEquals(at("2026-11-30T16:00"), n.wakeAt)
    }

    @Test fun crossesMidnight() {
        val p = plan(Schedule("night", "Wind down", setOf(1), "22:00", "06:00"))
        val n = BlockScheduler.compute(rules, p, at("2026-11-24T01:00"))
        assertTrue(n.focusRunning)
        assertEquals(at("2026-11-24T06:00"), n.wakeAt)
    }

    @Test fun emptyPlanSchedulesNothing() {
        val n = BlockScheduler.compute(rules, EmptyPlan, at("2026-11-23T09:00"))
        assertFalse(n.focusRunning)
        assertNull(n.wakeAt)
    }

    @Test fun neverWakesSoonerThanMinDelay() {
        val p = plan(Schedule("s", "Coaching block", setOf(1), "16:00", "17:30"))
        val now = at("2026-11-23T17:30").minusMillis(10)
        val n = BlockScheduler.compute(rules, p, now)
        assertTrue(n.focusRunning)
        assertEquals(now.plus(BlockScheduler.MIN_DELAY), n.wakeAt)
    }

    @Test fun focusTimeFormatting() {
        assertEquals("1:12", FocusTime.formatTimeLeft(Duration.ofMinutes(72)))
        assertEquals("1:12", FocusTime.formatTimeLeft(Duration.ofMinutes(71).plusSeconds(30)))
        assertEquals("1:00", FocusTime.formatTimeLeft(Duration.ofMinutes(60)))
        assertEquals("59 min", FocusTime.formatTimeLeft(Duration.ofMinutes(59)))
        assertEquals("1 min", FocusTime.formatTimeLeft(Duration.ofSeconds(5)))
        assertEquals("1 min", FocusTime.formatTimeLeft(Duration.ofSeconds(-5)))
        assertEquals("10:05", FocusTime.formatTimeLeft(Duration.ofMinutes(605)))
    }

    @Test fun focusTickIntervals() {
        assertEquals(30_000L, FocusTime.nextTickMillis(Duration.ofMinutes(71).plusSeconds(30)))
        assertEquals(60_000L, FocusTime.nextTickMillis(Duration.ofMinutes(5)))
        assertEquals(1_000L, FocusTime.nextTickMillis(Duration.ofMillis(200)))
        assertEquals(1_000L, FocusTime.nextTickMillis(Duration.ZERO))
    }
}
