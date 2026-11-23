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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class InterceptCopyTest {
    @Test fun sectionSurfaces_haveFriendlyNamesAndApps() {
        assertEquals(SurfaceCopy("Reels", "Instagram", plural = true, isSection = true), InterceptCopy.surface("instagram.reels"))
        assertEquals(SurfaceCopy("Shorts", "YouTube", plural = true, isSection = true), InterceptCopy.surface("youtube.shorts"))
        assertEquals(SurfaceCopy("Spotlight", "Snapchat", plural = false, isSection = true), InterceptCopy.surface("snapchat.spotlight"))
        assertEquals(SurfaceCopy("Facebook Reels", "Facebook", plural = true, isSection = true), InterceptCopy.surface("facebook.reels"))
    }

    @Test fun appSurfaces_useKnownLabelsOrPackageSegment() {
        assertEquals(SurfaceCopy("BGMI", "BGMI", plural = false, isSection = false), InterceptCopy.surface("app:com.pubg.imobile"))
        assertEquals("Chrome", InterceptCopy.surface("app:com.android.chrome").name)
        assertEquals("Ludoking", InterceptCopy.surface("app:com.ludo.king.ludoking").name)
        assertEquals("Spotify", InterceptCopy.appLabel("com.spotify.android"))
        assertEquals("Example", InterceptCopy.appLabel("com.example.android"))
    }

    @Test fun siteSurfaces_showDomain_andAreWholeBlocks() {
        val s = InterceptCopy.surface("site:www.youtube.com")
        assertEquals("youtube.com", s.name)
        assertEquals("youtube.com", s.appName)
        assertFalse(s.isSection)
    }

    @Test fun unknownSectionId_fallsBackToTitleCase() {
        val s = InterceptCopy.surface("tiktok.foryou")
        assertEquals("Foryou", s.name)
        assertEquals("Tiktok", s.appName)
        assertTrue(s.isSection)
        assertEquals("This app", InterceptCopy.surface("").name)
    }

    @Test fun blockPhrase() {
        assertEquals("Coaching block", InterceptCopy.blockPhrase("Coaching"))
        assertEquals("Night study block", InterceptCopy.blockPhrase(" Night study "))
        assertEquals("Morning Block", InterceptCopy.blockPhrase("Morning Block"))
        assertNull(InterceptCopy.blockPhrase(""))
        assertNull(InterceptCopy.blockPhrase("   "))
    }

    @Test fun downFrom_onlyWhenLowerThanUsual() {
        assertTrue(InterceptCopy.showDownFrom(3, 11))
        assertFalse(InterceptCopy.showDownFrom(11, 11))
        assertFalse(InterceptCopy.showDownFrom(12, 11))
        assertFalse(InterceptCopy.showDownFrom(0, 0))
    }

    @Test fun baseline_isRoundedMean() {
        assertEquals(0, InterceptCopy.baseline(emptyList()))
        assertEquals(11, InterceptCopy.baseline(listOf(10, 12, 11)))
        assertEquals(2, InterceptCopy.baseline(listOf(3, 2, 0, 0, 5, 1, 4))) // 15/7 = 2.14
        assertEquals(1, InterceptCopy.baseline(listOf(1, 0))) // 0.5 rounds up
    }
}

class BreathGateTest {
    @Test fun countdownSeconds() {
        assertEquals(5, BreathGate.secondsLeft(0))
        assertEquals(5, BreathGate.secondsLeft(1))
        assertEquals(4, BreathGate.secondsLeft(1_000))
        assertEquals(1, BreathGate.secondsLeft(4_999))
        assertEquals(0, BreathGate.secondsLeft(5_000))
        assertEquals(0, BreathGate.secondsLeft(60_000))
        assertEquals(5, BreathGate.secondsLeft(-100))
    }

    @Test fun closeIsOpenImmediately_softExitsAfterBreath() {
        val timeline = listOf(0L, 1_000L, 2_500L, 4_750L, 4_999L, 5_000L, 9_000L).map { it to BreathGate.exits(it) }
        timeline.forEach { (ms, g) ->
            assertTrue("close at $ms", g.close)
            assertEquals("swap at $ms", ms >= 5_000, g.swap)
            assertEquals("messages at $ms", ms >= 5_000, g.messages)
        }
    }
}

class InterceptViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 11, 24) // Tuesday
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant()
    private val plan = Plan(
        version = 1,
        distractions = listOf(Surface("instagram.reels", SurfaceMode.HIDE_SECTION)),
        schedules = listOf(Schedule("coaching", "Coaching", setOf(1, 2, 3, 4, 5), "16:00", "19:00")),
    )

    @Test fun recordsOpen_andBuildsStateWithBaseline() = runTest {
        val sessions = FakeSessionRepo(zone)
        // Previous week: 14 opens a day on 7 days → baseline 14; two earlier opens today.
        (1..7).forEach { d -> repeat(14) { sessions.recordIntercept("instagram.reels", at(day.minusDays(d.toLong()), 17)) } }
        repeat(2) { sessions.recordIntercept("instagram.reels", at(day, 9)) }
        val vm = InterceptViewModel(
            "instagram.reels", FakePlanRepository(plan), sessions, RulesEngine(zone), zone, MutableClock(at(day, 16, 7), zone),
        )
        val job = launch { vm.state.collect {} }
        val s = vm.state.first { it.opensToday == 3 }
        assertEquals("instagram.reels", s.surfaceId)
        assertEquals("Coaching", s.scheduleLabel)
        assertEquals(14, s.opensBaseline)
        assertEquals(1 + 7 * 14 + 2, sessions.intercepts.value.size)
        job.cancel()
    }

    @Test fun outsideBlock_hasNoScheduleLabel() {
        val s = InterceptStateBuilder.build("youtube.shorts", plan, RulesEngine(zone), at(day, 10), 1, listOf(0, 0))
        assertEquals("", s.scheduleLabel)
        assertEquals(0, s.opensBaseline)
    }
}
