package app.steady.feature.today

import app.steady.MainDispatcherRule
import app.steady.data.EmptyPlan
import app.steady.data.FakeDayRepository
import app.steady.data.FakePlanRepository
import app.steady.feature.setup.SetupDefaults
import app.steady.rules.Energy
import app.steady.rules.Plan
import app.steady.rules.RulesEngine
import app.steady.rules.Schedule
import app.steady.rules.Surface
import app.steady.rules.SurfaceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TodayTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = ZoneId.of("Asia/Kolkata")
    private val rules = RulesEngine(zone)
    private val tuesday = LocalDate.of(2026, 11, 24)
    private fun at(h: Int, m: Int = 0): Instant = tuesday.atTime(h, m).atZone(zone).toInstant()

    private val plan = Plan(
        distractions = listOf(Surface("instagram.reels", SurfaceMode.HIDE_SECTION)),
        schedules = SetupDefaults.presets,
    )

    @Test fun stateBeforeCoaching_showsCountdownAndStrip() {
        val s = TodayStateBuilder.build(plan, emptyList(), null, at(15, 48), rules, zone)
        assertEquals("Today · Tue 24 Nov", s.dateLabel)
        assertTrue(s.autoStartOn)
        assertNull(s.energy)
        assertEquals(25, s.blockMinutes) // OKAY default
        val next = s.next!!
        assertEquals(SetupDefaults.COACHING_ID, next.scheduleId)
        assertEquals("Coaching", next.label)
        assertEquals("4–7 pm", next.timeRange)
        assertEquals("in 12 min", next.status)
        assertFalse(next.isActive)
        assertEquals(listOf("8 am", "4–7 pm", "9–11 pm", "12 am"), s.strip.legend)
    }

    @Test fun duringBlock_isActive_andEnergyChangesMinutes() {
        val s = TodayStateBuilder.build(plan, emptyList(), Energy.SHARP, at(16, 16), rules, zone)
        assertEquals(40, s.blockMinutes)
        val next = s.next!!
        assertTrue(next.isActive)
        assertEquals("on now · until 7 pm", next.status)
        assertEquals(15, TodayStateBuilder.build(plan, emptyList(), Energy.LOW, at(9), rules, zone).blockMinutes)
    }

    @Test fun afterLastBlock_nextIsTomorrow() {
        val s = TodayStateBuilder.build(plan, emptyList(), null, at(23, 30), rules, zone)
        val next = s.next!!
        assertEquals("Tomorrow 4–7 pm", next.timeRange)
        assertEquals("in 16 h 30 min", next.status)
    }

    @Test fun emptyPlan_hasNoBlocks() {
        val s = TodayStateBuilder.build(EmptyPlan, emptyList(), null, at(10), rules, zone)
        assertFalse(s.autoStartOn)
        assertNull(s.next)
        assertEquals(1, s.strip.segments.size)
        assertFalse(s.strip.segments.single().isBlock)
    }

    @Test fun dayStrip_fractionsSumToOne_andMatchBlockLengths() {
        val strip = DayStrip.build(rules.blocksOn(plan, tuesday), tuesday, zone, at(12))
        // 8 am..12 am = 16 h. Gap 8h, block 3h, gap 2h, block 2h, gap 1h.
        val expected = listOf(8f, 3f, 2f, 2f, 1f).map { it / 16f }
        assertEquals(expected.size, strip.segments.size)
        strip.segments.zip(expected).forEach { (seg, e) -> assertEquals(e, seg.fraction, 1e-4f) }
        assertEquals(listOf(false, true, false, true, false), strip.segments.map { it.isBlock })
        assertEquals(4f / 16f, strip.nowFraction!!, 1e-4f)
    }

    @Test fun dayStrip_earlyAndMidnightCrossingBlocks() {
        val p = plan.copy(schedules = listOf(
            Schedule("early", "Early", setOf(2), "06:00", "07:00"),
            Schedule("late", "Late", setOf(2), "23:00", "01:00"),
        ))
        val strip = DayStrip.build(rules.blocksOn(p, tuesday), tuesday, zone)
        // window starts at 6 am (earlier than 8), ends at midnight: 18 h
        assertEquals("6 am", strip.legend.first())
        val sum = strip.segments.sumOf { it.fraction.toDouble() }
        assertEquals(1.0, sum, 1e-4)
        val last = strip.segments.last()
        assertTrue(last.isBlock) // late block clipped at midnight
        assertEquals(1f / 18f, last.fraction, 1e-4f)
        assertNull(strip.nowFraction)
    }

    @Test fun viewModel_combinesRepos_andHandlesActions() = runTest {
        val planRepo = FakePlanRepository(plan)
        val dayRepo = FakeDayRepository()
        val now = MutableStateFlow(at(15, 48))
        val vm = TodayViewModel(planRepo, dayRepo, rules, zone, now)
        val job = backgroundScope.launch { vm.state.collect {} }

        val s = vm.state.filter { !it.loading }.first()
        assertEquals("in 12 min", s.next!!.status)

        vm.setEnergy(Energy.LOW)
        assertEquals(Energy.LOW, dayRepo.energyState.value[tuesday])
        assertEquals(15, vm.state.value.blockMinutes)

        vm.setDraftTitle("Physics · Rotational motion")
        vm.setDraftStep("")
        assertTrue(vm.state.value.canAddTask)
        vm.addTask()
        val task = vm.state.value.tasks.single()
        assertEquals("Physics · Rotational motion", task.title)
        assertEquals("Open your books to the right page", task.firstStep) // default tiny step
        assertEquals("", vm.state.value.draftTitle)

        vm.toggleTask(task.id, true)
        assertTrue(vm.state.value.tasks.single().done)
        vm.deleteTask(task.id)
        assertTrue(vm.state.value.tasks.isEmpty())

        vm.setDraftTitle("  ")
        vm.addTask()
        assertTrue(dayRepo.taskState.value.isEmpty())

        now.value = at(16, 5)
        assertNotNull(vm.state.value.next?.takeIf { it.isActive })

        // New day: energy check-in resets.
        now.value = tuesday.plusDays(1).atTime(9, 0).atZone(zone).toInstant()
        assertNull(vm.state.value.energy)
        job.cancel()
    }
}
