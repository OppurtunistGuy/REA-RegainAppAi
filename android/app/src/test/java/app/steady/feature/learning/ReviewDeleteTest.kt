package app.steady.feature.learning

import app.steady.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ReviewDeleteTest {
    @get:Rule val main = MainDispatcherRule()
    private val now = Fx.now
    private val due = (1..3).map { Fx.card("d$it", now.minusSeconds(it * 60L)) }

    @Test fun removeKeepsPositionOnNextCard() {
        val s = ReviewSession(queueIds = listOf("a", "b", "c"), position = 1, revealed = true)
        assertEquals(ReviewSession(listOf("b", "c"), position = 0, revealed = true), ReviewQueue.remove(s, "a"))
        assertEquals(ReviewSession(listOf("a", "c"), position = 1, revealed = false), ReviewQueue.remove(s, "b"))
        assertEquals(s, ReviewQueue.remove(s, "zzz"))
    }

    @Test fun deleteNeedsConfirmation() = runTest {
        val repo = FakeCardRepository(due)
        val vm = ReviewViewModel(repo, MutableClock(now), Fx.zone, MutableStateFlow(now), Fx.ids())
        backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
        val target = vm.state.value.cards.first()
        vm.askDelete(target)
        assertEquals(target, vm.state.value.confirmDelete)
        vm.cancelDelete()
        assertNull(vm.state.value.confirmDelete)
        assertEquals(0, repo.deletes.size)

        vm.askDelete(target)
        vm.confirmDelete()
        assertEquals(listOf(listOf(target.id)), repo.deletes.map { it.toList() })
        assertEquals(2, vm.state.value.cards.size)
        assertEquals(2, vm.state.value.total)
        assertNull(vm.state.value.confirmDelete)
    }
}
