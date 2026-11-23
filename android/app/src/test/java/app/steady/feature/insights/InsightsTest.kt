package app.steady.feature.insights

import app.steady.MainDispatcherRule
import app.steady.feature.learning.FakeCardRepository
import app.steady.feature.learning.FakeSessionRepository
import app.steady.feature.learning.Fx
import app.steady.feature.learning.Fx.tue
import app.steady.feature.learning.MutableClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

class InsightsTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = Fx.zone
    private val now = tue(16, 7)
    private fun d(day: Int) = LocalDate.of(2026, 11, day)

    @Test fun weekStarts_areLastFourMondays() {
        assertEquals(listOf(d(2), d(9), d(16), d(23)), InsightsLogic.weekStarts(Fx.tuesday))
        assertEquals(listOf(d(2), d(9), d(16), d(23)), InsightsLogic.weekStarts(d(29))) // Sunday stays in its week
        val days = InsightsLogic.days(Fx.tuesday)
        assertEquals(28, days.size)
        assertEquals(d(2), days.first())
        assertEquals(d(29), days.last())
    }

    @Test fun focusMinutes_bucketByLocalWeek_inKolkata() {
        val sessions = listOf(
            // Mon 23 Nov 00:30 IST = Sun 22 Nov 19:00 UTC: belongs to the week of 23 Nov for the user.
            Fx.session("a", Fx.at(d(23), 0, 30), 60),
            // Sun 22 Nov 23:30 IST: last week, even though it ends after midnight.
            Fx.session("b", Fx.at(d(22), 23, 30), 45),
            Fx.session("c", Fx.at(d(3), 9), 25),
            Fx.session("old", Fx.at(d(1), 9), 90), // before the 4-week window
        )
        val s = InsightsLogic.build(sessions, emptyMap(), emptyMap(), emptyList(), now, zone)
        assertEquals(listOf(25, 0, 45, 60), s.weeks.map { it.focusMinutes })
        assertEquals(listOf("2 Nov", "9 Nov", "16 Nov", "23 Nov"), s.weeks.map { it.label })
        assertEquals(listOf(false, false, false, true), s.weeks.map { it.isCurrent })
        assertTrue(s.summary.anyData)
        assertEquals(60, s.summary.thisWeekMinutes)
        // Last week up to Tuesday had nothing, so this week is "up".
        assertEquals(Trend.UP, s.summary.trend)
    }

    @Test fun intercepts_andTopSurfaces() {
        val byDay = mapOf(d(2) to 4, d(22) to 3, d(23) to 2, d(24) to 1)
        val surfaces = mapOf("instagram.reels" to 5, "youtube.shorts" to 5, "app:com.pubg.imobile" to 7, "site:x.com" to 1, "zero" to 0)
        val s = InsightsLogic.build(emptyList(), byDay, surfaces, emptyList(), now, zone)
        assertEquals(listOf(4, 0, 3, 3), s.weeks.map { it.intercepts })
        assertEquals(3, s.summary.thisWeekIntercepts)
        assertEquals(listOf("BGMI" to 7, "Instagram Reels" to 5, "YouTube Shorts" to 5), s.topSurfaces.map { it.label to it.count })
        assertEquals("x.com", InsightsLogic.surfaceLabel("site:x.com"))
        assertEquals("com.foo", InsightsLogic.surfaceLabel("app:com.foo"))
    }

    @Test fun cardsDueNext7Days_overdueCountsToday() {
        val cards = listOf(
            Fx.card("overdue", now.minus(Duration.ofDays(2))),
            Fx.card("tonight", tue(23, 59)),
            Fx.card("wed", Fx.at(d(25), 0, 10)),
            Fx.card("mon", Fx.at(d(30), 12)),
            Fx.card("next-tue", Fx.at(LocalDate.of(2026, 12, 1), 0, 1)), // outside the 7 days
        )
        val s = InsightsLogic.build(emptyList(), emptyMap(), emptyMap(), cards, now, zone)
        assertEquals(listOf("Tue", "Wed", "Thu", "Fri", "Sat", "Sun", "Mon"), s.dueDays.map { it.label })
        assertEquals(listOf(2, 1, 0, 0, 0, 0, 1), s.dueDays.map { it.count })
        assertEquals(4, s.dueNext7)
    }

    @Test fun trend_isGentle() {
        assertEquals(Trend.NONE, InsightsLogic.trend(0, 0))
        assertEquals(Trend.SAME, InsightsLogic.trend(100, 95))
        assertEquals(Trend.SAME, InsightsLogic.trend(15, 10))
        assertEquals(Trend.UP, InsightsLogic.trend(200, 100))
        assertEquals(Trend.DOWN, InsightsLogic.trend(50, 100))
    }

    @Test fun emptyHistory_saysNothingLoggedYet() {
        val s = InsightsLogic.build(emptyList(), emptyMap(), emptyMap(), emptyList(), now, zone)
        assertFalse(s.summary.anyData)
        assertEquals(Trend.NONE, s.summary.trend)
        assertEquals(4, s.weeks.size)
        assertEquals(0, s.dueNext7)
    }

    @Test fun viewModel_readsDailyInterceptsForFourWeeks() = runTest {
        val repo = FakeSessionRepository(
            sessions = listOf(Fx.session("a", tue(10), 30)),
            intercepts = listOf(
                "instagram.reels" to tue(16),
                "instagram.reels" to Fx.at(d(9), 10),
                "youtube.shorts" to Fx.at(d(1), 10), // outside the window
            ),
        )
        val vm = InsightsViewModel(repo, FakeCardRepository(listOf(Fx.card("c", now))), MutableClock(now), zone)
        backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(listOf(0, 1, 0, 1), s.weeks.map { it.intercepts })
        assertEquals(30, s.weeks.last().focusMinutes)
        assertEquals(1, s.dueNext7)
        assertTrue(s.topSurfaces.isEmpty())
    }
}
