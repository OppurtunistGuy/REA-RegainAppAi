package app.steady.feature.focus

import app.steady.MainDispatcherRule
import app.steady.data.FakeDayRepository
import app.steady.data.FakePlanRepository
import app.steady.rules.Energy
import app.steady.rules.KeyHolder
import app.steady.rules.Plan
import app.steady.rules.Relation
import app.steady.rules.RulesEngine
import app.steady.rules.Schedule
import app.steady.rules.Surface
import app.steady.rules.SurfaceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class FocusViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 11, 24) // Tuesday
    private fun at(h: Int, m: Int = 0, s: Int = 0): Instant = day.atTime(h, m, s).atZone(zone).toInstant()

    private val basePlan = Plan(
        version = 1,
        distractions = listOf(Surface("instagram.reels", SurfaceMode.HIDE_SECTION)),
        schedules = listOf(Schedule("coaching", "Coaching", setOf(1, 2, 3, 4, 5), "16:00", "18:00")),
    )

    private class Env(val vm: FocusViewModel, val clock: MutableClock, val ticks: MutableStateFlow<Instant>,
                      val sessions: FakeSessionRepo, val unlocks: FakeUnlockRepo)

    private fun env(
        plan: Plan,
        start: Instant,
        energy: Energy? = null,
        unlockRepo: FakeUnlockRepo = FakeUnlockRepo(),
        routeId: String? = null,
        sessions: FakeSessionRepo = FakeSessionRepo(zone),
    ): Env {
        val clock = MutableClock(start, zone)
        val ticks = MutableStateFlow(start)
        val day = FakeDayRepository().apply { if (energy != null) energyState.value = mapOf(start.atZone(zone).toLocalDate() to energy) }
        val vm = FocusViewModel(
            FakePlanRepository(plan), day, sessions, unlockRepo, RulesEngine(zone), zone, clock, routeId, ticks, idGen = { "s1" },
        )
        return Env(vm, clock, ticks, sessions, unlockRepo)
    }

    private fun Env.advance(to: Instant) {
        clock.now = to
        ticks.value = to
    }

    private fun TestScope.collecting(e: Env) = launch { e.vm.state.collect {} }

    @Test fun manualBlock_finishSavesSessionWithCounts() = runTest {
        val sessions = FakeSessionRepo(zone).apply { recordIntercept("instagram.reels", at(9)) } // before: not counted
        val e = env(basePlan, at(10), energy = Energy.LOW, sessions = sessions)
        val job = collecting(e)
        val s0 = e.vm.state.first { !it.loading }
        assertEquals("15:00", s0.clockText)
        assertTrue(s0.canFinish)

        e.sessions.recordIntercept("instagram.reels", at(10, 5))
        e.sessions.recordIntercept("youtube.shorts", at(10, 6))
        e.unlocks.add(FocusPolicy.unlockFlow(basePlan).selfGrant(null, at(10, 7)))
        e.advance(at(10, 12))
        assertEquals("03:00", e.vm.state.first { it.clockText == "03:00" }.clockText)

        e.vm.finish()
        assertTrue(e.vm.state.first { it.saved }.saved)
        val saved = e.sessions.saved.value.single()
        assertEquals("s1", saved.id)
        assertNull(saved.scheduleId)
        assertEquals(at(10), saved.start)
        assertEquals(at(10, 12), saved.end)
        assertEquals(2, saved.intercepts)
        assertEquals(1, saved.unlocks)
        job.cancel()
    }

    @Test fun strictBlock_selfUnlockPath_addsGrant() = runTest {
        val e = env(basePlan, at(16, 30))
        val job = collecting(e)
        val s0 = e.vm.state.first { !it.loading }
        assertTrue(s0.showEmergencyUnlock)
        assertEquals("Locked until", "6:00 pm", s0.endsAtLabel)

        e.vm.openUnlock()
        e.vm.setReason("Lecture link on YouTube")
        e.vm.chooseSurface("instagram.reels")
        e.vm.submitUnlock() // still waiting: ignored
        assertEquals(UnlockPhase.Form, e.vm.state.value.unlock.phase)
        assertEquals(30, e.vm.state.value.unlockView.secondsLeft)

        e.advance(at(16, 30, 30))
        val ready = e.vm.state.first { it.unlockView.canSubmit }
        assertFalse(ready.unlockView.asksKeyHolder)
        e.vm.submitUnlock()
        val granted = e.vm.state.first { it.unlock.phase is UnlockPhase.Granted }
        assertEquals("4:45 pm", granted.grantedUntilLabel)
        val grant = e.unlocks.grantState.value.single()
        assertEquals("instagram.reels", grant.surfaceId)
        assertEquals(at(16, 30, 30), grant.from)
        assertEquals(at(16, 45, 30), grant.until)

        e.vm.dismissUnlock()
        assertFalse(e.vm.state.first { !it.unlock.isOpen }.unlock.isOpen)
        job.cancel()
    }

    @Test fun strictBlock_keyHolderPath_requestsAndShowsPending() = runTest {
        val plan = basePlan.copy(keyHolder = KeyHolder("k", "Asha", Relation.FRIEND))
        val e = env(plan, at(16, 30))
        val job = collecting(e)
        e.vm.state.first { !it.loading }
        e.vm.openUnlock()
        e.vm.setReason("short")
        e.advance(at(16, 31))
        val short = e.vm.state.first { it.unlockView.secondsLeft == 0 }
        assertEquals(ReasonHint.SHORT, short.unlockView.reasonHint)
        assertFalse(short.unlockView.canSubmit)
        e.vm.submitUnlock()
        assertTrue(e.unlocks.requests.isEmpty())

        e.vm.setReason("  Need the lecture link  ")
        e.vm.submitUnlock()
        val pending = e.vm.state.first { it.unlock.phase is UnlockPhase.Pending }
        assertEquals(UnlockPhase.Pending("req-1"), pending.unlock.phase)
        assertEquals("Asha", pending.keyHolderName)
        assertEquals(listOf<Pair<String?, String>>(null to "Need the lecture link"), e.unlocks.requests)
        assertTrue(e.unlocks.grantState.value.isEmpty())
        job.cancel()
    }

    @Test fun keyHolderRequestFailure_showsFailed() = runTest {
        val plan = basePlan.copy(keyHolder = KeyHolder("k", "Asha", Relation.PARENT))
        val e = env(plan, at(16, 30), unlockRepo = FakeUnlockRepo(fail = true))
        val job = collecting(e)
        e.vm.state.first { !it.loading }
        e.vm.openUnlock()
        e.vm.setReason("Need the lecture link")
        e.advance(at(16, 31))
        e.vm.state.first { it.unlockView.canSubmit }
        e.vm.submitUnlock()
        assertEquals(UnlockPhase.Failed, e.vm.state.first { it.unlock.phase == UnlockPhase.Failed }.unlock.phase)
        job.cancel()
    }

    @Test fun strictBlock_reachingEnd_allowsFinish_andSavesScheduleId() = runTest {
        val e = env(basePlan, at(17, 59))
        val job = collecting(e)
        assertFalse(e.vm.state.first { !it.loading }.canFinish)
        e.vm.finish() // strict and still running: ignored
        assertTrue(e.sessions.saved.value.isEmpty())
        e.advance(at(18))
        val done = e.vm.state.first { it.done }
        assertTrue(done.canFinish)
        e.vm.finish()
        e.vm.state.first { it.saved }
        job.cancel()
        assertEquals("coaching", e.sessions.saved.value.single().scheduleId)
    }
}
