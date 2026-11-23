package app.steady.data

import app.steady.data.local.LocalCardRepository
import app.steady.data.local.LocalDayRepository
import app.steady.data.local.LocalPlanRepository
import app.steady.data.local.LocalSessionRepository
import app.steady.data.local.LocalUnlockRepository
import app.steady.learning.Card
import app.steady.learning.FocusSession
import app.steady.learning.ReviewState
import app.steady.rules.Energy
import app.steady.rules.KeyHolder
import app.steady.rules.Plan
import app.steady.rules.PlanCodec
import app.steady.rules.Relation
import app.steady.rules.Schedule
import app.steady.rules.Surface
import app.steady.rules.SurfaceMode
import app.steady.rules.UnlockGrant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class LocalRepositoriesTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val now: Instant = Instant.parse("2026-11-24T10:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val plan = Plan(
        version = 3,
        distractions = listOf(Surface("instagram.reels", SurfaceMode.HIDE_SECTION)),
        schedules = listOf(Schedule("s1", "Coaching", setOf(1, 2, 3, 4, 5), "16:00", "19:00")),
        keyHolder = KeyHolder("k1", "Asha", Relation.FRIEND),
        siteBlocks = listOf("instagram.com"),
    )

    @Test fun planRepo_emitsEmptyPlanUntilSaved_thenRoundTrips() = runTest {
        val dao = FakePlanDao()
        val repo = LocalPlanRepository(dao, clock)
        val initial = repo.plan.first()
        assertEquals(EmptyPlan, initial)
        assertFalse(initial.isConfigured)

        repo.save(plan)
        assertEquals(plan, repo.plan.first())
        assertTrue(repo.plan.first().isConfigured)
        // Stored blob is exactly PlanCodec output, so other clients can read it.
        assertEquals(PlanCodec.encode(plan), dao.row.value!!.json)
        assertEquals(3, dao.row.value!!.version)
        assertEquals(now.toEpochMilli(), dao.row.value!!.updatedAtMs)
    }

    @Test fun sessionRepo_roundTripsSessions_andCountsInterceptsPerLocalDay() = runTest {
        val repo = LocalSessionRepository(FakeSessionDao(), zone)
        val s = FocusSession("a", "s1", now, now.plusSeconds(1500), intercepts = 2, unlocks = 0)
        repo.add(s)
        assertEquals(listOf(s), repo.sessions.first())

        // 2026-11-24 in Kolkata is [2026-11-23T18:30Z, 2026-11-24T18:30Z)
        repo.recordIntercept("instagram.reels", Instant.parse("2026-11-23T18:29:59Z")) // previous day
        repo.recordIntercept("instagram.reels", Instant.parse("2026-11-23T18:30:00Z"))
        repo.recordIntercept("youtube.shorts", Instant.parse("2026-11-24T18:29:59Z"))
        repo.recordIntercept("youtube.shorts", Instant.parse("2026-11-24T18:30:00Z")) // next day
        assertEquals(2, repo.interceptsOn(LocalDate.of(2026, 11, 24)).first())
        assertEquals(1, repo.interceptsOn(LocalDate.of(2026, 11, 23)).first())
    }

    @Test fun cardRepo_upsertsByIdAndKeepsReviewState() = runTest {
        val repo = LocalCardRepository(FakeCardDao())
        val card = Card("c1", "physics", "Solid sphere I?", "2/5 MR²", now, ReviewState(now, 1.5, 5.0, 2, 1))
        repo.upsert(listOf(card))
        val updated = card.copy(state = card.state.copy(reps = 3))
        repo.upsert(listOf(updated))
        assertEquals(listOf(updated), repo.cards.first())
    }

    @Test fun unlockRepo_storesGrantsAndRecordsPendingRequest() = runTest {
        val dao = FakeUnlockDao()
        val repo = LocalUnlockRepository(dao, clock) { "req-1" }
        val everything = UnlockGrant("g1", null, now, now.plusSeconds(900), null)
        val one = UnlockGrant("g2", "youtube.shorts", now, now.plusSeconds(600), "k1")
        repo.add(everything)
        repo.add(one)
        assertEquals(setOf(everything, one), repo.grants.first().toSet())

        assertEquals("req-1", repo.requestFromKeyHolder(null, "Need the class group link"))
        val req = dao.requests.value.single()
        assertNull(req.surfaceId)
        assertEquals("pending", req.status)
        assertEquals(now.toEpochMilli(), req.createdAtMs)
    }

    @Test fun dayRepo_tasksAndEnergy() = runTest {
        var n = 0
        val repo = LocalDayRepository(FakeDayDao(), clock) { "t${++n}" }
        val t1 = repo.addTask("  Physics ch 7 ", " Open NCERT to page 141 ")
        assertEquals("Physics ch 7", t1.title)
        assertEquals("Open NCERT to page 141", t1.firstStep)
        repo.addTask("Maths", "Find 3 PYQs")
        repo.setTaskDone("t1", true)
        val tasks = repo.tasks.first()
        assertEquals(listOf("t2", "t1"), tasks.map { it.id }) // done tasks sink to the bottom
        repo.deleteTask("t2")
        assertEquals(listOf("t1"), repo.tasks.first().map { it.id })

        val day = LocalDate.of(2026, 11, 24)
        assertNull(repo.energyOn(day).first())
        repo.setEnergy(day, Energy.SHARP)
        assertEquals(Energy.SHARP, repo.energyOn(day).first())
        assertNull(repo.energyOn(day.plusDays(1)).first())
    }

    @Test fun noopSync_andFreeEntitlement() = runTest {
        assertEquals(plan, NoopSyncApi.pushPlan(plan))
        assertNull(NoopSyncApi.pullPlan())
        assertNull(NoopSyncApi.detectors())
        val e = FreeEntitlementRepository().entitlement.first()
        assertEquals(Tier.FREE, e.tier)
        assertTrue(e.features.isEmpty())
    }
}
