package app.steady.learning

import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchedulerTest {
    private val t0 = Instant.parse("2026-11-23T09:00:00Z")

    private fun days(from: Instant, to: Instant) = Duration.between(from, to).seconds / 86_400.0

    @Test
    fun `new state is due now with defaults`() {
        val s = Scheduler.newState(t0)
        assertEquals(ReviewState(t0, 0.0, 5.0, 0, 0), s)
    }

    @Test
    fun `first GOOD is about one day and second about three`() {
        val s1 = Scheduler.review(Scheduler.newState(t0), Rating.GOOD, t0)
        assertEquals(1.0, days(t0, s1.due), 0.01)
        assertEquals(1, s1.reps)
        val t1 = s1.due
        val s2 = Scheduler.review(s1, Rating.GOOD, t1)
        assertEquals(3.0, days(t1, s2.due), 0.01)
        assertEquals(2, s2.reps)
        assertEquals(0, s2.lapses)
    }

    @Test
    fun `GOOD keeps growing`() {
        var s = Scheduler.newState(t0)
        var now = t0
        var last = 0.0
        repeat(4) {
            s = Scheduler.review(s, Rating.GOOD, now)
            val interval = days(now, s.due)
            assertTrue(interval > last, "interval $interval should exceed $last")
            last = interval
            now = s.due
        }
    }

    @Test
    fun `first HARD is shorter than first GOOD`() {
        val hard = Scheduler.review(Scheduler.newState(t0), Rating.HARD, t0)
        assertEquals(0.5, days(t0, hard.due), 0.01)
        assertEquals(6.0, hard.difficulty)
    }

    @Test
    fun `HARD grows stability less than GOOD`() {
        val base = ReviewState(t0, 4.0, 5.0, 3, 0)
        val hard = Scheduler.review(base, Rating.HARD, t0)
        val good = Scheduler.review(base, Rating.GOOD, t0)
        assertTrue(hard.stabilityDays > base.stabilityDays)
        assertTrue(hard.stabilityDays < good.stabilityDays)
        assertEquals(6.0, hard.stabilityDays, 1e-9)
        assertEquals(12.0, good.stabilityDays, 1e-9)
    }

    @Test
    fun `AGAIN resets interval to ten minutes and counts a lapse`() {
        val base = ReviewState(t0, 20.0, 5.0, 5, 1)
        val again = Scheduler.review(base, Rating.AGAIN, t0)
        assertEquals(Duration.ofMinutes(10), Duration.between(t0, again.due))
        assertEquals(2, again.lapses)
        assertEquals(6, again.reps)
        assertEquals(6.0, again.stabilityDays, 1e-9)
        assertEquals(7.0, again.difficulty)
    }

    @Test
    fun `AGAIN on a new card keeps it new so the next GOOD is one day`() {
        val again = Scheduler.review(Scheduler.newState(t0), Rating.AGAIN, t0)
        assertEquals(0.0, again.stabilityDays)
        assertEquals(1, again.lapses)
        val good = Scheduler.review(again, Rating.GOOD, again.due)
        assertEquals(1.0, good.stabilityDays, 1e-9)
    }

    @Test
    fun `AGAIN on a small stability floors at a quarter day`() {
        val again = Scheduler.review(ReviewState(t0, 0.5, 5.0, 1, 0), Rating.AGAIN, t0)
        assertEquals(0.25, again.stabilityDays, 1e-9)
    }

    @Test
    fun `difficulty is clamped to 1 to 10`() {
        var s = ReviewState(t0, 1.0, 9.5, 1, 0)
        repeat(3) { s = Scheduler.review(s, Rating.AGAIN, t0) }
        assertEquals(10.0, s.difficulty)
        s = Scheduler.review(ReviewState(t0, 1.0, 9.5, 1, 0), Rating.HARD, t0)
        assertEquals(10.0, s.difficulty)
        // Out-of-range input is clamped before use.
        val low = Scheduler.review(ReviewState(t0, 1.0, -3.0, 1, 0), Rating.GOOD, t0)
        assertTrue(low.difficulty in 1.0..10.0)
    }

    @Test
    fun `GOOD reverts difficulty toward 5`() {
        val hard = Scheduler.review(ReviewState(t0, 1.0, 10.0, 1, 0), Rating.GOOD, t0)
        assertEquals(9.0, hard.difficulty, 1e-9)
        val easy = Scheduler.review(ReviewState(t0, 1.0, 1.0, 1, 0), Rating.GOOD, t0)
        assertEquals(1.8, easy.difficulty, 1e-9)
    }

    @Test
    fun `intervals are capped at 365 days`() {
        val s = Scheduler.review(ReviewState(t0, 300.0, 1.0, 10, 0), Rating.GOOD, t0)
        assertEquals(365.0, s.stabilityDays)
        assertEquals(365.0, days(t0, s.due), 1e-9)
        var cur = Scheduler.newState(t0)
        var now = t0
        repeat(30) {
            cur = Scheduler.review(cur, Rating.GOOD, now)
            assertTrue(days(now, cur.due) <= 365.0)
            now = cur.due
        }
    }
}
