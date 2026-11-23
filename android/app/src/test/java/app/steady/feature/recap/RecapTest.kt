package app.steady.feature.recap

import app.steady.MainDispatcherRule
import app.steady.data.FakePlanRepository
import app.steady.feature.learning.FakeCardRepository
import app.steady.feature.learning.FakeSessionRepository
import app.steady.feature.learning.Fx
import app.steady.feature.learning.Fx.tue
import app.steady.feature.learning.MutableClock
import app.steady.rules.Plan
import app.steady.rules.Schedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class RecapTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = Fx.zone
    private val now = tue(16, 42)
    private val monday = LocalDate.of(2026, 11, 23)
    private val prototypeLines = listOf(
        "I depends on how mass is spread, not just total mass",
        "Parallel axis: I = I_cm + Md²",
        "Ring has bigger I than disc of same mass",
    )

    @Test fun weekRow_isMonToSun_inUserZone() {
        val sessions = listOf(
            Fx.session("mon", Fx.at(monday, 10), 30),
            // 00:30 IST on Tuesday is still Monday in UTC: it must count for Tuesday.
            Fx.session("tue", Fx.at(Fx.tuesday, 0, 30), 20),
            Fx.session("short", Fx.at(monday.plusDays(3), 9), 5), // under 10 min: not a done day
        )
        val (cells, progress) = RecapLogic.weekRow(sessions, Fx.tuesday, zone)
        assertEquals(listOf("M", "T", "W", "T", "F", "S", "S"), cells.map { it.letter })
        assertEquals(monday, cells.first().date)
        assertEquals(listOf(true, true, false, false, false, false, false), cells.map { it.done })
        assertEquals(listOf(false, true, false, false, false, false, false), cells.map { it.isToday })
        assertEquals(4, progress.targetDays)
        assertEquals(2, progress.remainingDays)
        assertFalse(progress.met)
    }

    @Test fun stats_forToday() {
        val sessions = listOf(
            Fx.session("y", Fx.at(monday, 16), 50, intercepts = 4, unlocks = 2),
            Fx.session("a", tue(10), 25),
            Fx.session("b", tue(15, 42), 25, intercepts = 2, unlocks = 1),
        )
        val s = RecapStateBuilder.build(sessions, interceptsToday = 2, now = now, zone = zone, lines = List(3) { "" }, saved = null)
        assertFalse(s.loading)
        assertEquals(25, s.blockMinutes)
        assertEquals(50, s.focusedMinutesToday)
        assertEquals(2, s.urgesToday)
        assertEquals(1, s.unlocksToday)
        assertEquals(listOf(true, true, false, false, false, false, false), s.week.map { it.done })
        assertFalse(s.canSave)

        val none = RecapStateBuilder.build(emptyList(), 0, now, zone, List(3) { "" }, null)
        assertNull(none.blockMinutes)
        assertEquals(0, none.focusedMinutesToday)
        assertEquals(4, none.goalRemaining)
    }

    @Test fun recallLines_becomeCards_withProjectedDays() {
        val (cards, saved) = RecapLogic.toCards(prototypeLines + "", "coaching", now, zone, Fx.ids())
        assertEquals(3, cards.size)
        assertEquals("Parallel axis", cards[1].front)
        assertEquals("I = I_cm + Md²", cards[1].back)
        assertTrue(cards.all { it.deckId == "coaching" && it.state.due == now })
        assertEquals(SavedRecall(3, listOf("Wed", "Sat")), saved)

        val (none, nothing) = RecapLogic.toCards(listOf("", "ok", "  "), "notes", now, zone, Fx.ids())
        assertTrue(none.isEmpty())
        assertEquals(SavedRecall(0, emptyList()), nothing)
    }

    @Test fun canSave_rules_and_setLine() {
        val blank = RecapUiState(lines = List(3) { "" })
        assertFalse(blank.canSave)
        val typed = blank.copy(lines = RecapLogic.setLine(blank.lines, 1, "a\nb"))
        assertEquals("a b", typed.lines[1])
        assertTrue(typed.canSave)
        assertTrue(typed.copy(saved = SavedRecall(0, emptyList())).canSave) // retry after 0 cards
        assertFalse(typed.copy(saved = SavedRecall(2, emptyList())).canSave)
        assertTrue(typed.copy(saved = SavedRecall(2, emptyList())).linesLocked)
        assertEquals(blank.lines, RecapLogic.setLine(blank.lines, 7, "x"))
        assertEquals(RecapLogic.MAX_LINE, RecapLogic.setLine(blank.lines, 0, "x".repeat(400))[0].length)
    }

    @Test fun deck_comesFromLatestBlocksSchedule() {
        val plan = Plan(distractions = emptyList(), schedules = listOf(Schedule("c", "Coaching", setOf(2), "16:00", "19:00")))
        assertEquals("coaching", RecapLogic.deckFor(listOf(Fx.session("a", tue(16), 25, scheduleId = "c")), plan))
        assertEquals("notes", RecapLogic.deckFor(listOf(Fx.session("a", tue(16), 25)), plan))
        assertEquals("notes", RecapLogic.deckFor(emptyList(), plan))
    }

    @Test fun viewModel_savesCardsOnce() = runTest {
        val plan = Plan(distractions = emptyList(), schedules = listOf(Schedule("c", "Coaching", setOf(2), "16:00", "19:00")))
        val sessions = FakeSessionRepository(
            sessions = listOf(Fx.session("b", tue(16, 10), 25, scheduleId = "c")),
            intercepts = listOf("instagram.reels" to tue(16, 20), "youtube.shorts" to tue(16, 25), "instagram.reels" to Fx.at(monday, 9)),
        )
        val cards = FakeCardRepository()
        val vm = RecapViewModel(sessions, cards, FakePlanRepository(plan), MutableClock(now), zone, MutableStateFlow(now), Fx.ids())
        backgroundScope.launch(main.dispatcher) { vm.state.collect {} }

        assertEquals(2, vm.state.value.urgesToday)
        assertEquals(25, vm.state.value.blockMinutes)
        prototypeLines.forEachIndexed { i, l -> vm.setLine(i, l) }
        assertTrue(vm.state.value.canSave)
        vm.save()
        assertEquals(3, cards.state.value.size)
        assertTrue(cards.state.value.all { it.deckId == "coaching" })
        assertEquals(SavedRecall(3, listOf("Wed", "Sat")), vm.state.value.saved)

        vm.save()
        vm.setLine(0, "changed")
        assertEquals(1, cards.upserts.size)
        assertEquals(prototypeLines[0], vm.state.value.lines[0])
    }
}
