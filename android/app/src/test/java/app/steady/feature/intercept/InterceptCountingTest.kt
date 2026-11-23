package app.steady.feature.intercept

import app.steady.MainDispatcherRule
import app.steady.data.FakePlanRepository
import app.steady.feature.focus.FakeSessionRepo
import app.steady.feature.focus.MutableClock
import app.steady.rules.Plan
import app.steady.rules.RulesEngine
import app.steady.rules.Schedule
import app.steady.rules.Surface
import app.steady.rules.SurfaceMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Phase 12: one recorder per open, overlay numbers like the in-app sheet, strict flag. */
class InterceptCountingTest {
    @get:Rule val main = MainDispatcherRule()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 11, 24)
    private fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant()
    private val plan = Plan(
        version = 1,
        distractions = listOf(Surface("instagram.reels", SurfaceMode.HIDE_SECTION)),
        schedules = listOf(
            Schedule("coaching", "Coaching", setOf(1, 2, 3, 4, 5), "16:00", "19:00"),
            Schedule("evening", "Evening", setOf(1, 2, 3, 4, 5), "20:00", "22:00", strict = false),
        ),
    )

    @Test fun inAppScreenWithoutDebugFlagDoesNotRecord() = runTest {
        val sessions = FakeSessionRepo(zone)
        val vm = InterceptViewModel(
            "instagram.reels", FakePlanRepository(plan), sessions, RulesEngine(zone), zone, MutableClock(at(day, 17), zone),
            recordOpen = false,
        )
        backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
        vm.state.first { it.scheduleLabel == "Coaching" }
        assertTrue(sessions.intercepts.value.isEmpty())
    }

    @Test fun overlayStateUsesBaselineOfPreviousDays() = runTest {
        val sessions = FakeSessionRepo(zone)
        (1..7).forEach { d -> repeat(10) { sessions.recordIntercept("instagram.reels", at(day.minusDays(d.toLong()), 17)) } }
        sessions.recordIntercept("instagram.reels", at(day, 17)) // the service records first
        val s = InterceptStateBuilder.forOverlay("instagram.reels", "Coaching", strict = true, sessions, day)
        assertEquals(1, s.opensToday)
        assertEquals(10, s.opensBaseline)
        assertEquals("Coaching", s.scheduleLabel)
        assertTrue(s.strict)
    }

    @Test fun strictFollowsRunningBlock() {
        val rules = RulesEngine(zone)
        assertTrue(InterceptStateBuilder.build("instagram.reels", plan, rules, at(day, 17), 0, emptyList()).strict)
        assertFalse(InterceptStateBuilder.build("instagram.reels", plan, rules, at(day, 21), 0, emptyList()).strict)
        assertTrue(InterceptStateBuilder.strict(plan, rules, at(day, 10)))
    }
}
