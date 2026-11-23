package app.steady.rules

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RulesEngineTest {
    private val utc = RulesEngine(ZoneId.of("UTC"))
    private val all = (1..7).toSet()

    private fun plan(vararg s: Schedule, distractions: List<Surface> = listOf(Surface("youtube.shorts", SurfaceMode.HIDE_SECTION)),
                     siteBlocks: List<String> = emptyList()) =
        Plan(distractions = distractions, schedules = s.toList(), siteBlocks = siteBlocks)

    private fun t(s: String) = Instant.parse(s)

    // 2026-11-23 is a Monday.

    @Test
    fun `empty plan is idle with no next block`() {
        val p = plan()
        assertEquals(BlockState.Idle, utc.state(p, t("2026-11-23T10:00:00Z")))
        assertNull(utc.nextBlock(p, t("2026-11-23T10:00:00Z")))
        assertTrue(utc.blocksOn(p, LocalDate.of(2026, 11, 23)).isEmpty())
    }

    @Test
    fun `strict wins over non-strict even if non-strict ends later`() {
        val p = plan(
            Schedule("a", "A", all, "09:00", "12:00", strict = false),
            Schedule("b", "B", all, "10:00", "11:00", strict = true),
        )
        val s = assertIs<BlockState.Active>(utc.state(p, t("2026-11-23T10:30:00Z")))
        assertEquals("b", s.scheduleId)
        assertTrue(s.strict)
    }

    @Test
    fun `later end wins among equal strictness, then lowest id`() {
        val p = plan(
            Schedule("z", "Z", all, "09:00", "12:00"),
            Schedule("y", "Y", all, "10:00", "11:00"),
            Schedule("x", "X", all, "08:00", "12:00"),
        )
        val s = assertIs<BlockState.Active>(utc.state(p, t("2026-11-23T10:30:00Z")))
        assertEquals("x", s.scheduleId)
        assertEquals(t("2026-11-23T08:00:00Z"), s.startedAt)
    }

    @Test
    fun `non-strict schedule is reported as non-strict`() {
        val p = plan(Schedule("a", "A", all, "09:00", "12:00", strict = false))
        val s = assertIs<BlockState.Active>(utc.state(p, t("2026-11-23T09:00:00Z")))
        assertFalse(s.strict)
    }

    @Test
    fun `midnight crossing belongs to start day only`() {
        val p = plan(Schedule("late", "Late", setOf(1), "23:00", "02:00")) // Monday only
        assertIs<BlockState.Active>(utc.state(p, t("2026-11-24T01:59:00Z"))) // Tue 01:59, from Monday
        assertEquals(BlockState.Idle, utc.state(p, t("2026-11-24T02:00:00Z")))
        assertEquals(BlockState.Idle, utc.state(p, t("2026-11-23T01:00:00Z"))) // Mon 01:00, Sunday not scheduled
        val s = assertIs<BlockState.Active>(utc.state(p, t("2026-11-23T23:00:00Z")))
        assertEquals(t("2026-11-24T02:00:00Z"), s.endsAt)
    }

    @Test
    fun `end equal to start means 24 hours`() {
        val p = plan(Schedule("d", "Day", setOf(1), "00:00", "00:00"))
        val s = assertIs<BlockState.Active>(utc.state(p, t("2026-11-23T23:59:00Z")))
        assertEquals(t("2026-11-24T00:00:00Z"), s.endsAt)
    }

    @Test
    fun `active state lists distractions and site blocks`() {
        val p = plan(
            Schedule("a", "A", all, "09:00", "12:00"),
            distractions = listOf(Surface("youtube.shorts", SurfaceMode.HIDE_SECTION), Surface("site:reddit.com", SurfaceMode.BLOCK_APP)),
            siteBlocks = listOf("instagram.com", "reddit.com"),
        )
        val s = assertIs<BlockState.Active>(utc.state(p, t("2026-11-23T10:00:00Z")))
        assertEquals(setOf("youtube.shorts", "site:reddit.com", "site:instagram.com"), s.blocked.map { it.id }.toSet())
        assertEquals(3, s.blocked.size)
    }

    @Test
    fun `nextBlock is strictly after now and looks a week ahead`() {
        val p = plan(Schedule("w", "Weekly", setOf(1), "09:00", "10:00")) // Mondays
        assertEquals(t("2026-11-30T09:00:00Z"), utc.nextBlock(p, t("2026-11-23T09:00:00Z"))?.startsAt)
        assertEquals(t("2026-11-23T09:00:00Z"), utc.nextBlock(p, t("2026-11-23T08:59:59Z"))?.startsAt)
        assertEquals("Weekly", utc.nextBlock(p, t("2026-11-23T08:00:00Z"))?.label)
    }

    @Test
    fun `nextBlock tie on start uses active ordering`() {
        val p = plan(
            Schedule("b", "B", all, "09:00", "10:00", strict = false),
            Schedule("c", "C", all, "09:00", "10:00"),
            Schedule("a", "A", all, "09:00", "10:00"),
        )
        assertEquals("a", utc.nextBlock(p, t("2026-11-23T08:00:00Z"))?.scheduleId)
    }

    @Test
    fun `blocksOn lists blocks starting that day in order`() {
        val p = plan(
            Schedule("night", "Night", all, "21:00", "01:00"),
            Schedule("morning", "Morning", setOf(1), "07:00", "08:00"),
            Schedule("tue", "Tue", setOf(2), "07:00", "08:00"),
        )
        val blocks = utc.blocksOn(p, LocalDate.of(2026, 11, 23))
        assertEquals(listOf("morning", "night"), blocks.map { it.scheduleId })
        assertEquals(t("2026-11-24T01:00:00Z"), blocks[1].endsAt)
    }

    @Test
    fun `isBlocked requires listed surface and active block`() {
        val p = plan(Schedule("a", "A", all, "09:00", "12:00"), siteBlocks = listOf("instagram.com"))
        val inBlock = t("2026-11-23T10:00:00Z")
        assertTrue(utc.isBlocked(p, emptyList(), "youtube.shorts", inBlock))
        assertFalse(utc.isBlocked(p, emptyList(), "instagram.reels", inBlock))
        assertTrue(utc.isBlocked(p, emptyList(), "site:instagram.com", inBlock))
        assertTrue(utc.isBlocked(p, emptyList(), "site:www.instagram.com", inBlock))
        assertFalse(utc.isBlocked(p, emptyList(), "site:notinstagram.com", inBlock))
        assertFalse(utc.isBlocked(p, emptyList(), "youtube.shorts", t("2026-11-23T12:00:00Z")))
    }

    @Test
    fun `grants unblock until they expire`() {
        val p = plan(Schedule("a", "A", all, "09:00", "12:00"))
        val from = t("2026-11-23T10:00:00Z")
        val g = UnlockGrant("g1", "youtube.shorts", from, from.plusSeconds(15 * 60), approvedBy = "kh1")
        assertTrue(utc.isBlocked(p, listOf(g), "youtube.shorts", from.minusSeconds(1)))
        assertFalse(utc.isBlocked(p, listOf(g), "youtube.shorts", from))
        assertFalse(utc.isBlocked(p, listOf(g), "youtube.shorts", from.plusSeconds(15 * 60 - 1)))
        assertTrue(utc.isBlocked(p, listOf(g), "youtube.shorts", from.plusSeconds(15 * 60)))
    }

    @Test
    fun `grant for one surface does not cover another, null covers all`() {
        val p = plan(
            Schedule("a", "A", all, "09:00", "12:00"),
            distractions = listOf(Surface("youtube.shorts", SurfaceMode.HIDE_SECTION), Surface("instagram.reels", SurfaceMode.HIDE_SECTION)),
        )
        val now = t("2026-11-23T10:00:00Z")
        val one = UnlockGrant("g1", "youtube.shorts", now, now.plusSeconds(60), null)
        assertFalse(utc.isBlocked(p, listOf(one), "youtube.shorts", now))
        assertTrue(utc.isBlocked(p, listOf(one), "instagram.reels", now))
        val all = UnlockGrant("g2", null, now, now.plusSeconds(60), null)
        assertFalse(utc.isBlocked(p, listOf(all), "instagram.reels", now))
    }

    // DST: America/New_York springs forward on Sunday 2026-03-08 at 02:00 EST → 03:00 EDT.
    private val ny = RulesEngine(ZoneId.of("America/New_York"))

    @Test
    fun `DST spring forward shortens a block that spans the gap`() {
        val p = plan(Schedule("s", "S", setOf(7), "01:00", "04:00"))
        val s = assertIs<BlockState.Active>(ny.state(p, t("2026-03-08T07:30:00Z"))) // 03:30 EDT
        assertEquals(t("2026-03-08T06:00:00Z"), s.startedAt) // 01:00 EST
        assertEquals(t("2026-03-08T08:00:00Z"), s.endsAt) // 04:00 EDT, 2 real hours later
    }

    @Test
    fun `DST start inside the gap shifts forward`() {
        val p = plan(Schedule("g", "Gap", setOf(7), "02:30", "05:00"))
        val b = ny.blocksOn(p, LocalDate.of(2026, 3, 8)).single()
        assertEquals(t("2026-03-08T07:30:00Z"), b.startsAt) // 03:30 EDT
        assertEquals(BlockState.Idle, ny.state(p, t("2026-03-08T07:29:00Z")))
        assertIs<BlockState.Active>(ny.state(p, t("2026-03-08T07:30:00Z")))
    }

    @Test
    fun `DST offsets apply on each side of the transition`() {
        val p = plan(Schedule("n", "Night", all, "21:00", "23:00"))
        assertEquals(t("2026-03-08T02:00:00Z"), ny.blocksOn(p, LocalDate.of(2026, 3, 7)).single().startsAt) // EST
        assertEquals(t("2026-03-09T01:00:00Z"), ny.blocksOn(p, LocalDate.of(2026, 3, 8)).single().startsAt) // EDT
        assertEquals(t("2026-03-09T01:00:00Z"), ny.nextBlock(p, t("2026-03-08T12:00:00Z"))?.startsAt)
    }

    @Test
    fun `DST midnight-crossing block across the transition`() {
        val p = plan(Schedule("o", "Overnight", setOf(6), "22:00", "06:00")) // Saturday
        val s = assertIs<BlockState.Active>(ny.state(p, t("2026-03-08T09:59:00Z")))
        assertEquals(t("2026-03-08T03:00:00Z"), s.startedAt) // 22:00 EST Sat
        assertEquals(t("2026-03-08T10:00:00Z"), s.endsAt) // 06:00 EDT Sun
        assertEquals(BlockState.Idle, ny.state(p, t("2026-03-08T10:00:00Z")))
    }
}
