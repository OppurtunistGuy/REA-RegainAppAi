package app.steady.feature.focus

import app.steady.MainDispatcherRule
import app.steady.data.FakeDayRepository
import app.steady.data.FakePlanRepository
import app.steady.data.UnlockRepository
import app.steady.remote.NoKeyHolderLinked
import app.steady.remote.NotSignedIn
import app.steady.remote.UnlockOutcome
import app.steady.rules.KeyHolder
import app.steady.rules.Plan
import app.steady.rules.Relation
import app.steady.rules.RulesEngine
import app.steady.rules.Schedule
import app.steady.rules.Surface
import app.steady.rules.SurfaceMode
import app.steady.rules.UnlockGrant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Phase 12: the unlock sheet follows RemoteUnlockRepository outcomes and explains missing setup. */
class UnlockOutcomeTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = LocalDate.of(2026, 11, 24)
    private fun at(h: Int, m: Int = 0): Instant = day.atTime(h, m).atZone(zone).toInstant()
    private val plan = Plan(
        version = 1,
        distractions = listOf(Surface("instagram.reels", SurfaceMode.HIDE_SECTION)),
        schedules = listOf(Schedule("coaching", "Coaching", setOf(1, 2, 3, 4, 5), "16:00", "18:00")),
        keyHolder = KeyHolder("k", "Asha", Relation.FRIEND),
    )

    private class ThrowingUnlocks(val error: Exception?) : UnlockRepository {
        override val grants: Flow<List<UnlockGrant>> = MutableStateFlow(emptyList())
        override suspend fun add(g: UnlockGrant) = Unit
        override suspend fun requestFromKeyHolder(surfaceId: String?, reason: String): String = error?.let { throw it } ?: "r1"
    }

    private fun TestScope.submitted(unlocks: UnlockRepository, outcomes: MutableStateFlow<Map<String, UnlockOutcome>>): FocusViewModel {
        val clock = MutableClock(at(16, 30), zone)
        val ticks = MutableStateFlow(clock.now)
        val vm = FocusViewModel(
            FakePlanRepository(plan), FakeDayRepository(), FakeSessionRepo(zone), unlocks, RulesEngine(zone), zone, clock,
            null, ticks, idGen = { "s1" }, unlockOutcomes = outcomes,
        )
        backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
        vm.openUnlock()
        vm.setReason("Need the lecture link")
        clock.now = at(16, 31)
        ticks.value = clock.now
        vm.submitUnlock()
        return vm
    }

    @Test fun approvedOutcomeShowsGrantedMinutes() = runTest {
        val outcomes = MutableStateFlow<Map<String, UnlockOutcome>>(emptyMap())
        val vm = submitted(ThrowingUnlocks(null), outcomes)
        assertEquals(UnlockPhase.Pending("r1"), vm.state.first { it.unlock.phase is UnlockPhase.Pending }.unlock.phase)
        val grant = UnlockGrant("kh-r1", null, at(16, 32), at(16, 47), "k")
        outcomes.value = mapOf("r1" to UnlockOutcome.Approved(grant))
        val s = vm.state.first { it.unlock.phase is UnlockPhase.Granted }
        assertEquals(UnlockPhase.Granted(at(16, 47), 15), s.unlock.phase)
        assertEquals(null, s.unlock.pendingRequestId)
    }

    @Test fun deniedOutcomeShowsDenied() = runTest {
        val outcomes = MutableStateFlow<Map<String, UnlockOutcome>>(emptyMap())
        val vm = submitted(ThrowingUnlocks(null), outcomes)
        vm.state.first { it.unlock.phase is UnlockPhase.Pending }
        vm.dismissUnlock() // closed while waiting: the decision reopens it with the news
        outcomes.value = mapOf("r1" to UnlockOutcome.Denied)
        assertEquals(UnlockPhase.Denied, vm.state.first { it.unlock.phase == UnlockPhase.Denied }.unlock.phase)
    }

    @Test fun timedOutShowsNoAnswer_andOtherIdsIgnored() = runTest {
        val outcomes = MutableStateFlow<Map<String, UnlockOutcome>>(emptyMap())
        val vm = submitted(ThrowingUnlocks(null), outcomes)
        vm.state.first { it.unlock.phase is UnlockPhase.Pending }
        outcomes.value = mapOf("other" to UnlockOutcome.Denied)
        assertEquals(UnlockPhase.Pending("r1"), vm.state.value.unlock.phase)
        outcomes.value = mapOf("r1" to UnlockOutcome.TimedOut)
        assertEquals(UnlockPhase.NoAnswer, vm.state.first { it.unlock.phase == UnlockPhase.NoAnswer }.unlock.phase)
    }

    @Test fun notSignedInAsksToSignIn() = runTest {
        val vm = submitted(ThrowingUnlocks(NotSignedIn()), MutableStateFlow(emptyMap()))
        assertEquals(UnlockPhase.NeedsSignIn, vm.state.first { it.unlock.phase == UnlockPhase.NeedsSignIn }.unlock.phase)
    }

    @Test fun noKeyHolderLinkedPointsToPartner() = runTest {
        val vm = submitted(ThrowingUnlocks(NoKeyHolderLinked()), MutableStateFlow(emptyMap()))
        assertEquals(UnlockPhase.NeedsKeyHolder, vm.state.first { it.unlock.phase == UnlockPhase.NeedsKeyHolder }.unlock.phase)
    }
}
