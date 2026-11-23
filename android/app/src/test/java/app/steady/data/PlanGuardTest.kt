package app.steady.data

import app.steady.data.PlanGuard.Verdict
import app.steady.data.PlanGuard.Weakening
import app.steady.rules.KeyHolder
import app.steady.rules.Plan
import app.steady.rules.Relation
import app.steady.rules.RulesEngine
import app.steady.rules.Schedule
import app.steady.rules.Surface
import app.steady.rules.SurfaceMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

class PlanGuardTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val rules = RulesEngine(zone)
    private val day = LocalDate.of(2026, 11, 24) // Tuesday
    private fun at(h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant()
    private val coaching = Schedule("coaching", "Coaching", setOf(1, 2, 3, 4, 5), "16:00", "19:00")
    private val night = Schedule("night", "Night", setOf(1, 2, 3, 4, 5, 6, 7), "21:00", "23:00")
    private val plan = Plan(
        version = 3,
        distractions = listOf(Surface("instagram.reels", SurfaceMode.HIDE_SECTION), Surface("app:com.pubg.imobile", SurfaceMode.BLOCK_APP)),
        schedules = listOf(coaching, night),
        keyHolder = KeyHolder("k", "Asha", Relation.FRIEND),
    )
    private val inBlock = at(17)

    private fun reasons(proposed: Plan, now: java.time.Instant = inBlock) =
        (PlanGuard.check(plan, proposed, rules, now) as? Verdict.Locked)?.reasons.orEmpty()

    @Test fun outsideBlocksAnythingGoes() {
        assertEquals(Verdict.Allowed, PlanGuard.check(plan, plan.copy(distractions = emptyList(), schedules = emptyList()), rules, at(10)))
    }

    @Test fun nonStrictBlockDoesNotLock() {
        val soft = plan.copy(schedules = listOf(coaching.copy(strict = false)))
        assertEquals(Verdict.Allowed, PlanGuard.check(soft, soft.copy(schedules = emptyList()), rules, inBlock))
    }

    @Test fun strengtheningIsAllowed() {
        val more = plan.copy(
            version = 4,
            distractions = plan.distractions + Surface("youtube.shorts", SurfaceMode.HIDE_SECTION),
            schedules = plan.schedules + Schedule("x", "Extra", setOf(6), "08:00", "09:00"),
        )
        assertEquals(Verdict.Allowed, PlanGuard.check(plan, more, rules, inBlock))
        // Extending the running block is fine too.
        assertEquals(Verdict.Allowed, PlanGuard.check(plan, plan.copy(schedules = listOf(coaching.copy(end = "20:00"), night)), rules, inBlock))
    }

    @Test fun weakeningsAreLocked() {
        assertEquals(listOf(Weakening.DISTRACTION_REMOVED), reasons(plan.copy(distractions = plan.distractions.drop(1))))
        assertEquals(
            listOf(Weakening.DISTRACTION_SOFTENED),
            reasons(plan.copy(distractions = listOf(plan.distractions[0], Surface("app:com.pubg.imobile", SurfaceMode.HIDE_SECTION)))),
        )
        assertEquals(listOf(Weakening.SCHEDULE_REMOVED), reasons(plan.copy(schedules = listOf(coaching))))
        assertEquals(listOf(Weakening.ACTIVE_BLOCK_CHANGED), reasons(plan.copy(schedules = listOf(coaching.copy(end = "18:00"), night))))
        assertEquals(listOf(Weakening.ACTIVE_BLOCK_CHANGED), reasons(plan.copy(schedules = listOf(coaching.copy(strict = false), night))))
        assertEquals(listOf(Weakening.KEY_HOLDER_CHANGED), reasons(plan.copy(keyHolder = null)))
        assertEquals(
            listOf(Weakening.UNLOCK_POLICY_CHANGED),
            reasons(plan.copy(unlockPolicy = plan.unlockPolicy.copy(waitSeconds = 5))),
        )
        val locked = PlanGuard.check(plan, plan.copy(keyHolder = null), rules, inBlock) as Verdict.Locked
        assertEquals(at(19), locked.until)
    }

    @Test fun guardedRepositoryRefusesWeakerSave() = runTest {
        val inner = FakePlanRepository(plan)
        val repo = GuardedPlanRepository(inner, { rules }, Clock.fixed(inBlock, zone))
        try {
            repo.save(plan.copy(version = 4, schedules = listOf(night)))
            fail("expected PlanLockedException")
        } catch (e: PlanLockedException) {
            assertEquals(at(19), e.until)
        }
        assertEquals(plan, repo.plan.first())
        val stronger = plan.copy(version = 4, distractions = plan.distractions + Surface("youtube.shorts", SurfaceMode.HIDE_SECTION))
        repo.save(stronger)
        assertTrue(repo.plan.first() == stronger)
    }
}
