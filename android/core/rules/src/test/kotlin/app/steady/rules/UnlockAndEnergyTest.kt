package app.steady.rules

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class UnlockAndEnergyTest {
    private val t0 = Instant.parse("2026-11-23T10:00:00Z")
    private val good = "need maps for directions"

    @Test
    fun `waiting until wait elapses`() {
        val f = UnlockFlow(UnlockPolicy(waitSeconds = 30), hasKeyHolder = false)
        assertEquals(UnlockStep.WAITING, f.step(t0, t0, good))
        assertEquals(UnlockStep.WAITING, f.step(t0, t0.plusSeconds(29), good))
        assertEquals(UnlockStep.READY, f.step(t0, t0.plusSeconds(30), good))
    }

    @Test
    fun `secondsLeft rounds up and never goes negative`() {
        val f = UnlockFlow(UnlockPolicy(waitSeconds = 30), hasKeyHolder = false)
        assertEquals(30, f.secondsLeft(t0, t0))
        assertEquals(30, f.secondsLeft(t0, t0.plusMillis(1)))
        assertEquals(1, f.secondsLeft(t0, t0.plusMillis(29_001)))
        assertEquals(0, f.secondsLeft(t0, t0.plusSeconds(30)))
        assertEquals(0, f.secondsLeft(t0, t0.plusSeconds(999)))
    }

    @Test
    fun `reason needs 8 non-space characters`() {
        val f = UnlockFlow(UnlockPolicy(waitSeconds = 0), hasKeyHolder = false)
        assertEquals(UnlockStep.NEEDS_REASON, f.step(t0, t0, ""))
        assertEquals(UnlockStep.NEEDS_REASON, f.step(t0, t0, "a b c d e f g")) // 7 non-space
        assertEquals(UnlockStep.NEEDS_REASON, f.step(t0, t0, "        "))
        assertEquals(UnlockStep.READY, f.step(t0, t0, "a b c d e f g h"))
        assertEquals(UnlockStep.READY, f.step(t0, t0, "12345678"))
    }

    @Test
    fun `reason not required when policy says so`() {
        val f = UnlockFlow(UnlockPolicy(waitSeconds = 0, requireReason = false), hasKeyHolder = false)
        assertEquals(UnlockStep.READY, f.step(t0, t0, ""))
    }

    @Test
    fun `key holder needed after wait and reason`() {
        val f = UnlockFlow(UnlockPolicy(waitSeconds = 30), hasKeyHolder = true)
        assertEquals(UnlockStep.WAITING, f.step(t0, t0, good))
        assertEquals(UnlockStep.NEEDS_REASON, f.step(t0, t0.plusSeconds(30), "short"))
        assertEquals(UnlockStep.NEEDS_KEYHOLDER, f.step(t0, t0.plusSeconds(30), good))
    }

    @Test
    fun `key holder not needed when notify is off or none exists`() {
        assertEquals(UnlockStep.READY,
            UnlockFlow(UnlockPolicy(waitSeconds = 0, notifyKeyHolder = false), hasKeyHolder = true).step(t0, t0, good))
        assertEquals(UnlockStep.READY,
            UnlockFlow(UnlockPolicy(waitSeconds = 0, notifyKeyHolder = true), hasKeyHolder = false).step(t0, t0, good))
    }

    @Test
    fun `selfGrant uses approvalMinutes by default`() {
        val f = UnlockFlow(UnlockPolicy(approvalMinutes = 15), hasKeyHolder = false)
        val g = f.selfGrant("youtube.shorts", t0)
        assertEquals("youtube.shorts", g.surfaceId)
        assertEquals(t0, g.from)
        assertEquals(t0.plusSeconds(15 * 60), g.until)
        assertNull(g.approvedBy)
        val g2 = f.selfGrant(null, t0, minutes = 5)
        assertNull(g2.surfaceId)
        assertEquals(t0.plusSeconds(300), g2.until)
        assertNotEquals(g.id, g2.id)
        assertFailsWith<IllegalArgumentException> { f.selfGrant(null, t0, minutes = 0) }
    }

    @Test
    fun `self grant expires in the engine`() {
        val engine = RulesEngine(java.time.ZoneOffset.UTC)
        val plan = Plan(
            distractions = listOf(Surface("youtube.shorts", SurfaceMode.HIDE_SECTION)),
            schedules = listOf(Schedule("a", "A", (1..7).toSet(), "09:00", "12:00")),
        )
        val g = UnlockFlow(UnlockPolicy(approvalMinutes = 10), false).selfGrant(null, t0)
        assertEquals(false, engine.isBlocked(plan, listOf(g), "youtube.shorts", t0.plusSeconds(599)))
        assertEquals(true, engine.isBlocked(plan, listOf(g), "youtube.shorts", t0.plusSeconds(600)))
    }

    @Test
    fun `energy planner block lengths`() {
        assertEquals(15, EnergyPlanner.blockMinutes(Energy.LOW))
        assertEquals(25, EnergyPlanner.blockMinutes(Energy.OKAY))
        assertEquals(40, EnergyPlanner.blockMinutes(Energy.SHARP))
    }
}
