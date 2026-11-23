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

class SwapTest {
    @get:Rule val main = MainDispatcherRule()

    private val zone = Fx.zone
    private val now = Fx.now

    @Test fun timer_countsDownFromSixty() {
        assertEquals(60, SwapTimer.secondsLeft(now, now))
        assertEquals(60, SwapTimer.secondsLeft(now, now.minusSeconds(5))) // clock skew never adds time
        assertEquals(42, SwapTimer.secondsLeft(now, now.plusSeconds(18)))
        assertEquals(0, SwapTimer.secondsLeft(now, now.plusSeconds(600)))
        assertEquals("1:00", SwapTimer.label(60))
        assertEquals("0:42", SwapTimer.label(42))
        assertEquals("0:05", SwapTimer.label(5))
        assertEquals("0:00", SwapTimer.label(-1))
        assertEquals(1f, SwapTimer.fraction(60))
        assertEquals(0.5f, SwapTimer.fraction(30))
        assertEquals(0f, SwapTimer.fraction(0))
    }

    @Test fun reducer_locksPickedCard_andRatingNeedsReveal() {
        val overdue = Fx.card("old", now.minusSeconds(3600))
        val fresh = Fx.card("new", now.plusSeconds(3600))
        var s = SwapReducer.ensurePicked(SwapSession(now), listOf(fresh, overdue), now)
        assertEquals("old", s.cardId)
        assertFalse(SwapReducer.canRate(s))
        assertEquals(s, SwapReducer.rated(s, Rating.GOOD)) // ignored before reveal

        s = SwapReducer.reveal(s)
        assertEquals(SwapPhase.ANSWER, s.phase)
        s = SwapReducer.rated(s, Rating.HARD)
        assertEquals(SwapPhase.RATED, s.phase)
        assertEquals(Rating.HARD, s.rating)
        assertEquals(s, SwapReducer.rated(s, Rating.GOOD)) // only once

        // A new card list (e.g. after the upsert) never swaps the card under the user.
        val newer = Fx.card("newest", now.minusSeconds(99_999))
        assertEquals("old", SwapReducer.ensurePicked(s, listOf(overdue, newer), now).cardId)
    }

    @Test fun reducer_noCards_staysEmpty_thenTimerStartsWhenCardArrives() {
        val start = SwapSession(now)
        assertEquals(start, SwapReducer.ensurePicked(start, emptyList(), now))
        assertEquals(start, SwapReducer.reveal(start))
        val later = now.plusSeconds(30)
        val s = SwapReducer.ensurePicked(start.copy(showAddForm = true), listOf(Fx.card("a", later)), later)
        assertEquals("a", s.cardId)
        assertEquals(later, s.startedAt)
        assertFalse(s.showAddForm)
    }

    @Test fun builder_emptyState_and_loading() {
        val loading = SwapStateBuilder.build(null, SwapSession(now), now, zone)
        assertTrue(loading.loading)
        assertFalse(loading.isEmpty)

        val empty = SwapStateBuilder.build(emptyList(), SwapSession(now), now, zone)
        assertTrue(empty.isEmpty)
        assertNull(empty.deckName)
    }

    @Test fun builder_previewsGotItDay_thenShowsRealNextDue() {
        val c = Fx.card("a", now)
        val s = SwapSession(now.minusSeconds(18), cardId = "a", phase = SwapPhase.ANSWER)
        val ui = SwapStateBuilder.build(listOf(c), s, now, zone)
        assertEquals(42, ui.secondsLeft)
        assertEquals("tomorrow’s", ui.reviewDay)
        assertEquals("physics", ui.deckName)
        assertNull(ui.nextDueLabel)

        // A card already seen once: "Got it" brings it back in 3 days (Friday).
        val seen = ReviewFlow.rate(c, Rating.GOOD, now.minus(Duration.ofDays(1)))
        assertEquals("Friday’s", SwapStateBuilder.build(listOf(seen), s, now, zone).reviewDay)

        val rated = ReviewFlow.rate(c, Rating.AGAIN, now)
        val after = SwapStateBuilder.build(listOf(rated), s.copy(phase = SwapPhase.RATED, rating = Rating.AGAIN), now, zone)
        assertEquals("today’s", after.reviewDay)
        assertEquals("in 10 min", after.nextDueLabel)
    }

    @Test fun viewModel_emptyThenSamples_thenRateUpserts() = runTest {
        val repo = FakeCardRepository()
        val clock = MutableClock(now)
        val ticks = MutableStateFlow(now)
        val vm = SwapViewModel(repo, clock, zone, ticks, Fx.ids())
        backgroundScope.launch(main.dispatcher) { vm.state.collect {} }

        assertTrue(vm.state.value.isEmpty)
        vm.addSamples()
        val card = vm.state.value.card
        assertNotNull(card)
        assertEquals(3, repo.state.value.size)
        assertEquals(SwapPhase.QUESTION, vm.state.value.phase)

        vm.rate(Rating.GOOD) // ignored, answer not shown yet
        assertEquals(1, repo.upserts.size)

        vm.reveal()
        clock.instant = now.plusSeconds(20)
        ticks.value = clock.instant
        assertEquals(40, vm.state.value.secondsLeft)
        vm.rate(Rating.GOOD)
        assertEquals(2, repo.upserts.size)
        val saved = repo.upserts.last().single()
        assertEquals(card!!.id, saved.id)
        assertEquals(clock.instant.plus(Duration.ofDays(1)), saved.state.due)
        assertEquals(SwapPhase.RATED, vm.state.value.phase)
        assertEquals(card.id, vm.state.value.card!!.id)
        assertEquals("tomorrow, 4:07 pm", vm.state.value.nextDueLabel)
    }

    @Test fun viewModel_manualCard() = runTest {
        val repo = FakeCardRepository()
        val vm = SwapViewModel(repo, MutableClock(now), zone, MutableStateFlow(now), Fx.ids())
        backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
        vm.showAddForm(true)
        assertTrue(vm.state.value.showAddForm)
        vm.setFront("Unit of torque?")
        vm.saveDraft() // back is blank: nothing saved
        assertTrue(repo.upserts.isEmpty())
        vm.setBack("N·m")
        vm.saveDraft()
        assertEquals("Unit of torque?", repo.state.value.single().front)
        assertEquals("Unit of torque?", vm.state.value.card!!.front)
        assertFalse(vm.state.value.showAddForm)
        assertNull(vm.state.value.deckName) // generic notes deck -> "Back to focus"
    }
}
