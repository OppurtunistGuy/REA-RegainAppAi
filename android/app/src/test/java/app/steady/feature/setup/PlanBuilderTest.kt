package app.steady.feature.setup

import app.steady.data.EmptyPlan
import app.steady.rules.KeyHolder
import app.steady.rules.Plan
import app.steady.rules.PlanCodec
import app.steady.rules.Relation
import app.steady.rules.Surface
import app.steady.rules.SurfaceMode
import app.steady.rules.UnlockPolicy
import app.steady.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class PlanBuilderTest {
    private var n = 0
    private val ids = { "id${++n}" }

    @Test fun firstPlan_fromPresetsAndFriend() {
        val plan = PlanBuilder.build(
            SetupSelection(setOf("instagram.reels", "youtube.shorts", "app:com.pubg.imobile"), SetupDefaults.presets, Relation.FRIEND, " Asha "),
            EmptyPlan, ids,
        )
        assertEquals(1, plan.version)
        assertEquals(
            listOf(
                Surface("instagram.reels", SurfaceMode.HIDE_SECTION),
                Surface("youtube.shorts", SurfaceMode.HIDE_SECTION),
                Surface("app:com.pubg.imobile", SurfaceMode.BLOCK_APP),
            ),
            plan.distractions,
        )
        assertEquals(KeyHolder("id1", "Asha", Relation.FRIEND), plan.keyHolder)
        assertTrue(plan.unlockPolicy.notifyKeyHolder)
        // Round-trips through the shared codec.
        assertEquals(plan, PlanCodec.decode(PlanCodec.encode(plan)))
    }

    @Test fun presetsMatchPrototypeCopy() {
        val (coaching, night) = SetupDefaults.presets
        assertEquals("Coaching 4–7 pm Mon–Fri", scheduleSummary(coaching))
        assertEquals("Night study 9–11 pm daily", scheduleSummary(night))
        assertEquals(setOf(1, 2, 3, 4, 5), coaching.days)
        assertEquals("16:00", coaching.start)
        assertEquals("19:00", coaching.end)
        assertTrue(coaching.strict && night.strict)
    }

    @Test fun self_meansNoExternalKeyHolder() {
        val prev = Plan(version = 4, distractions = emptyList(), schedules = emptyList(),
            keyHolder = KeyHolder("k", "Mum", Relation.PARENT), unlockPolicy = UnlockPolicy(waitSeconds = 45))
        val plan = PlanBuilder.build(SetupSelection(setOf("youtube.shorts"), SetupDefaults.presets.take(1), Relation.SELF, "ignored"), prev, ids)
        assertEquals(5, plan.version)
        assertNull(plan.keyHolder)
        assertFalse(plan.unlockPolicy.notifyKeyHolder)
        assertEquals(45, plan.unlockPolicy.waitSeconds) // keeps the rest of the policy
    }

    @Test fun keepsKeyHolderIdWhenUnchanged_andPreservesUnknownSurfacesAndSites() {
        val prev = Plan(
            version = 2,
            distractions = listOf(Surface("instagram.reels", SurfaceMode.HIDE_SECTION), Surface("site:reddit.com", SurfaceMode.BLOCK_APP)),
            schedules = SetupDefaults.presets,
            keyHolder = KeyHolder("k-stable", "Mum", Relation.PARENT),
            siteBlocks = listOf("reddit.com"),
        )
        val same = PlanBuilder.build(SetupSelection(setOf("youtube.shorts"), prev.schedules, Relation.PARENT, "Mum"), prev, ids)
        assertEquals("k-stable", same.keyHolder!!.id)
        assertEquals(listOf("youtube.shorts", "site:reddit.com"), same.distractions.map { it.id })
        assertEquals(listOf("reddit.com"), same.siteBlocks)

        val renamed = PlanBuilder.build(SetupSelection(setOf("youtube.shorts"), prev.schedules, Relation.PARENT, "Papa"), prev, ids)
        assertEquals("id1", renamed.keyHolder!!.id)
    }

    @Test fun draftToSchedule_andValidation() {
        val d = ScheduleDraft(label = " Sunday revision ", days = setOf(7), start = LocalTime.of(10, 0), end = LocalTime.of(12, 30))
        assertTrue(d.isValid)
        val s = d.toSchedule { "x" }
        assertEquals("x", s.id)
        assertEquals("Sunday revision", s.label)
        assertEquals("10:00", s.start)
        assertEquals("12:30", s.end)
        assertEquals(d.copy(id = "x", label = "Sunday revision"), ScheduleDraft.from(s))

        assertFalse(d.copy(label = " ").isValid)
        assertFalse(d.copy(days = emptySet()).isValid)
        assertFalse(d.copy(end = d.start).isValid)
    }

    @Test fun formatHelpers() {
        assertEquals("Sun", Format.days(setOf(7)))
        assertEquals("weekends", Format.days(setOf(6, 7)))
        assertEquals("Tue–Thu", Format.days(setOf(2, 3, 4)))
        assertEquals("Mon, Wed", Format.days(setOf(1, 3)))
        assertEquals("11 am–1 pm", Format.timeRange("11:00", "13:00"))
        assertEquals("11 pm–1 am", Format.timeRange("23:00", "01:00"))
        assertEquals("12 am", Format.time(LocalTime.MIDNIGHT))
        assertEquals("4:30 pm", Format.time(LocalTime.of(16, 30)))
    }
}
