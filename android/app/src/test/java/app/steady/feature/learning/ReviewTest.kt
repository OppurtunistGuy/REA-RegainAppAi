package app.steady.feature.learning

import app.steady.MainDispatcherRule
import app.steady.learning.Rating
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Duration

class ReviewTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = Fx.zone
    private val now = Fx.now
    private val due = (1..3).map { Fx.card("d$it", now.minusSeconds(it * 60L)) }
    private val later = Fx.card("later", now.plus(Duration.ofDays(1)))

    @Test fun queue_freezesDueCards_andShowsProgress() {
        var s = ReviewQueue.ensureStarted(ReviewSession(), due + later, now)
        assertEquals(listOf("d3", "d2", "d1"), s.queueIds)
        // Already started: a new list does not reshuffle.
        assertEquals(s, ReviewQueue.ensureStarted(s, listOf(later), now))

        var ui = ReviewStateBuilder.build(due + later, s, now, zone)
        assertEquals("d3", ui.current!!.id)
        assertEquals(1, ui.position)
        assertEquals(3, ui.total)
        assertFalse(ui.revealed)

        s = ReviewQueue.reveal(s)
        assertTrue(ReviewStateBuilder.build(due + later, s, now, zone).revealed)
        s = ReviewQueue.advance(ReviewQueue.advance(s))
        ui = ReviewStateBuilder.build(due + later, s, now, zone)
        assertEquals("d1", ui.current!!.id)
        assertEquals(3, ui.position)
        assertFalse(ui.revealed)

        s = ReviewQueue.advance(ReviewQueue.advance(s)) // past the end stays at the end
        ui = ReviewStateBuilder.build(due + later, s, now, zone)
        assertTrue(ui.done)
        assertTrue(ui.hadQueue)
        assertEquals("now", ui.nextDue) // d1..d3 unrated in this pure test
    }

    @Test fun nothingDue_and_noCards() {
        val s = ReviewQueue.ensureStarted(ReviewSession(), listOf(later), now)
        val ui = ReviewStateBuilder.build(listOf(later), s, now, zone)
        assertTrue(ui.done)
        assertFalse(ui.hadQueue)
        assertEquals("tomorrow, 4:07 pm", ui.nextDue)
        assertEquals("tomorrow, 4:07 pm", ui.dueLabels["later"])

        val none = ReviewStateBuilder.build(emptyList(), ReviewQueue.ensureStarted(ReviewSession(), emptyList(), now), now, zone)
        assertTrue(none.done)
        assertNull(none.nextDue)
        assertTrue(ReviewStateBuilder.build(null, ReviewSession(), now, zone).loading)
        assertEquals(ReviewSession(), ReviewQueue.reveal(ReviewSession()))
    }

    @Test fun viewModel_ratesThroughQueue_thenDone() = runTest {
        val repo = FakeCardRepository(due + later)
        val vm = ReviewViewModel(repo, MutableClock(now), zone, MutableStateFlow(now), Fx.ids())
        backgroundScope.launch(main.dispatcher) { vm.state.collect {} }

        assertEquals(3, vm.state.value.total)
        vm.rate(Rating.GOOD) // not revealed: ignored
        assertTrue(repo.upserts.isEmpty())

        vm.reveal(); vm.rate(Rating.AGAIN)
        vm.reveal(); vm.rate(Rating.HARD)
        assertEquals("d1", vm.state.value.current!!.id)
        assertEquals(3, vm.state.value.position)
        vm.reveal(); vm.rate(Rating.GOOD)

        val st = vm.state.value
        assertTrue(st.done)
        assertEquals(3, repo.upserts.size)
        assertEquals(now.plus(Duration.ofMinutes(10)), repo.state.value.first { it.id == "d3" }.state.due)
        assertEquals("in 10 min", st.nextDue)

        vm.checkAgain()
        assertTrue(vm.state.value.done) // the "Again" card is not due for 10 more minutes
    }

    @Test fun viewModel_addAndEditCards() = runTest {
        val repo = FakeCardRepository(listOf(later))
        val vm = ReviewViewModel(repo, MutableClock(now), zone, MutableStateFlow(now), Fx.ids())
        backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
        assertTrue(vm.state.value.done)

        vm.startAdd()
        assertNotNull(vm.state.value.editor)
        vm.setFront("Angular momentum?")
        vm.setBack("L = Iω")
        vm.saveEdit()
        assertNull(vm.state.value.editor)
        assertEquals(2, repo.state.value.size)
        // The new card is due now, so the queue was re-read.
        assertEquals("Angular momentum?", vm.state.value.current!!.front)

        vm.startEdit(later)
        vm.setBack("edited")
        vm.saveEdit()
        val edited = repo.state.value.first { it.id == "later" }
        assertEquals("edited", edited.back)
        assertEquals(later.state, edited.state)

        vm.startEdit(later)
        vm.cancelEdit()
        assertNull(vm.state.value.editor)
    }
}
