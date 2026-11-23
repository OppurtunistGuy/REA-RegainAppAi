package app.steady.feature.learning

import app.steady.feature.learning.Fx.tue
import app.steady.learning.Rating
import app.steady.learning.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class LearningLogicTest {
    private val zone = Fx.zone
    private val now = Fx.now

    @Test fun starterCards_areThreePhysicsCardsDueNow() {
        val cards = StarterCards.physics(now, Fx.ids())
        assertEquals(3, cards.size)
        assertEquals(listOf("id1", "id2", "id3"), cards.map { it.id })
        assertTrue(cards.all { it.deckId == Decks.PHYSICS && it.state.due == now && it.createdAt == now })
        assertEquals("I = ⅖ MR²", cards.first().back)
    }

    @Test fun cardDraft_requiresBothSides_andTrimsLength() {
        assertFalse(CardDraft().canSave)
        assertFalse(CardDraft(front = "Q", back = "  ").canSave)
        assertTrue(CardDraft(front = "Q", back = "A").canSave)
        assertEquals(CardLimits.MAX_SIDE, CardDraft().withFront("x".repeat(500)).front.length)
    }

    @Test fun cardEditing_newCardIsDueNow_editKeepsSchedule() {
        val new = CardEditing.apply(CardDraft(front = " Torque? ", back = " r × F "), emptyList(), now, Fx.ids())!!
        assertEquals("id1", new.id)
        assertEquals("Torque?", new.front)
        assertEquals("r × F", new.back)
        assertEquals(Decks.NOTES, new.deckId)
        assertEquals(Scheduler.newState(now), new.state)

        val reviewed = ReviewFlow.rate(Fx.card("c1", now), Rating.GOOD, now)
        val edited = CardEditing.apply(CardDraft.of(reviewed).withBack("new back"), listOf(reviewed), now.plusSeconds(99), Fx.ids())!!
        assertEquals("c1", edited.id)
        assertEquals("new back", edited.back)
        assertEquals(reviewed.state, edited.state)
        assertEquals(reviewed.createdAt, edited.createdAt)
        assertEquals(Decks.PHYSICS, edited.deckId)

        assertNull(CardEditing.apply(CardDraft(front = "", back = "x"), emptyList(), now, Fx.ids()))
    }

    @Test fun reviewFlow_ratingMapsToScheduler() {
        val c = Fx.card("c", now)
        assertEquals(now.plus(Duration.ofMinutes(10)), ReviewFlow.rate(c, Rating.AGAIN, now).state.due)
        assertEquals(now.plus(Duration.ofHours(12)), ReviewFlow.rate(c, Rating.HARD, now).state.due)
        assertEquals(now.plus(Duration.ofDays(1)), ReviewFlow.rate(c, Rating.GOOD, now).state.due)
        assertEquals(listOf(Rating.AGAIN, Rating.HARD, Rating.GOOD), RatingOrder)
    }

    @Test fun dueQueue_mostOverdueFirst_newestOnTies_skipsFuture() {
        val a = Fx.card("a", now.minusSeconds(60))
        val b = Fx.card("b", now.minusSeconds(3600))
        val c = Fx.card("c", now.minusSeconds(3600), createdAt = now.plusSeconds(1))
        val future = Fx.card("f", now.plusSeconds(1))
        assertEquals(listOf("c", "b", "a"), ReviewFlow.dueQueue(listOf(a, b, c, future), now).map { it.id })
    }

    @Test fun reviewDay_possessiveLabels() {
        assertEquals("today’s", DueLabels.reviewDay(now.minusSeconds(5), now, zone))
        assertEquals("today’s", DueLabels.reviewDay(tue(23, 0), now, zone))
        assertEquals("tomorrow’s", DueLabels.reviewDay(now.plus(Duration.ofDays(1)), now, zone))
        assertEquals("Thursday’s", DueLabels.reviewDay(now.plus(Duration.ofDays(2)), now, zone))
        assertEquals("Monday’s", DueLabels.reviewDay(now.plus(Duration.ofDays(6)), now, zone))
        assertEquals("the 4 Dec", DueLabels.reviewDay(now.plus(Duration.ofDays(10)), now, zone))
    }

    @Test fun reviewDay_usesZoneNotUtc() {
        // 00:30 IST Wednesday is still Tuesday in UTC: it is "tomorrow" for the user.
        val lateNow = tue(23, 50)
        val due = lateNow.plus(Duration.ofMinutes(40))
        assertEquals("tomorrow’s", DueLabels.reviewDay(due, lateNow, zone))
    }

    @Test fun relativeDue() {
        assertEquals("now", DueLabels.relative(now, now, zone))
        assertEquals("in 10 min", DueLabels.relative(now.plus(Duration.ofMinutes(10)), now, zone))
        assertEquals("today, 9 pm", DueLabels.relative(tue(21), now, zone))
        assertEquals("tomorrow, 4:07 pm", DueLabels.relative(now.plus(Duration.ofDays(1)), now, zone))
        assertEquals("Fri, 4:07 pm", DueLabels.relative(now.plus(Duration.ofDays(3)), now, zone))
        assertEquals("4 Dec", DueLabels.relative(now.plus(Duration.ofDays(10)), now, zone))
    }

    @Test fun projection_firstTwoGotItReviews() {
        val fresh = Scheduler.newState(now)
        val times = ReviewProjection.nextDueTimes(fresh, now)
        assertEquals(listOf(now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(4))), times)
        assertEquals(listOf("Wed", "Sat"), ReviewProjection.dayLabels(listOf(Fx.card("x", now)), now, zone))
        assertEquals(emptyList<String>(), ReviewProjection.dayLabels(emptyList(), now, zone))
    }

    @Test fun decksAndMinutes() {
        assertEquals("physics", Decks.displayName("physics"))
        assertNull(Decks.displayName(Decks.NOTES))
        assertNull(Decks.displayName("  "))
        assertEquals("coaching", Decks.fromLabel(" Coaching "))
        assertEquals(Decks.NOTES, Decks.fromLabel(null))
        assertEquals("48m", Minutes.short(48))
        assertEquals("2h", Minutes.short(120))
        assertEquals("1h 20m", Minutes.short(80))
        assertEquals("0m", Minutes.short(-3))
        assertEquals("45 min", Minutes.long(45))
        assertEquals("3 h 10 min", Minutes.long(190))
        assertEquals("1 h", Minutes.long(60))
    }
}
