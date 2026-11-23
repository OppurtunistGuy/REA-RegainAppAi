package app.steady.learning

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecallToCardsTest {
    private val now = Instant.parse("2026-11-23T10:00:00Z")

    private fun convert(vararg lines: String): List<Card> {
        var n = 0
        return RecallToCards.fromRecall(lines.toList(), "physics", now) { "c${++n}" }
    }

    private fun one(line: String): Pair<String, String> {
        val cards = convert(line)
        assertEquals(1, cards.size, "expected one card for '$line'")
        return cards[0].front to cards[0].back
    }

    @Test
    fun `prototype parallel axis keeps unicode and splits on colon first`() {
        assertEquals("Parallel axis" to "I = I_cm + Md²", one("Parallel axis: I = I_cm + Md²"))
    }

    @Test
    fun `prototype ring vs disc becomes a cloze on the longest word`() {
        assertEquals("Ring has _____ I than disc of same mass" to "bigger", one("Ring has bigger I than disc of same mass"))
    }

    @Test
    fun `equals pattern with unicode`() {
        assertEquals("I" to "I_cm + Md²", one("I = I_cm + Md²"))
        assertEquals("v₀" to "initial velocity", one("  v₀=initial velocity  "))
    }

    @Test
    fun `colon pattern`() {
        assertEquals("Mitochondria" to "powerhouse of the cell", one("Mitochondria:  powerhouse of the cell"))
    }

    @Test
    fun `spaced dash pattern including en and em dash`() {
        assertEquals("Torque" to "force times lever arm", one("Torque - force times lever arm"))
        assertEquals("Torque" to "r × F", one("Torque – r × F"))
        assertEquals("Torque" to "r × F", one("Torque — r × F"))
    }

    @Test
    fun `hyphenated words are not a dash separator`() {
        assertEquals("_____-of-mass frame" to "center", one("center-of-mass frame"))
    }

    @Test
    fun `is pattern`() {
        assertEquals("Photosynthesis" to "light to sugar", one("Photosynthesis is light to sugar"))
        assertEquals("Entropy" to "disorder", one("Entropy IS disorder"))
    }

    @Test
    fun `is inside a word is not a separator`() {
        assertEquals("This _____ much" to "proves", one("This proves much"))
    }

    @Test
    fun `colon without following space is not a separator`() {
        assertEquals("Exam at 10:30 about _____" to "torque", one("Exam at 10:30 about torque"))
    }

    @Test
    fun `earliest separator wins`() {
        assertEquals("Momentum" to "p = mv", one("Momentum is p = mv"))
        assertEquals("Momentum" to "p = mv", one("Momentum: p = mv"))
        assertEquals("Work" to "energy: transferred", one("Work = energy: transferred"))
    }

    @Test
    fun `empty side falls back to cloze`() {
        assertEquals("_____ =" to "Energy", one("Energy ="))
    }

    @Test
    fun `cloze ties pick the first longest word`() {
        assertEquals("_____ beats fluff" to "Steel", one("Steel beats fluff"))
    }

    @Test
    fun `blank and very short lines are skipped`() {
        assertTrue(convert("", "   ", "abc", " ab ").isEmpty())
    }

    @Test
    fun `lines without pattern or long word give no card`() {
        assertTrue(convert("a b c d e").isEmpty())
    }

    @Test
    fun `cards get ids deck time and a fresh schedule`() {
        val cards = convert("F = ma", "", "Ring has bigger I than disc of same mass", "Parallel axis: I = I_cm + Md²")
        assertEquals(listOf("c1", "c2", "c3"), cards.map { it.id })
        cards.forEach {
            assertEquals("physics", it.deckId)
            assertEquals(now, it.createdAt)
            assertEquals(Scheduler.newState(now), it.state)
        }
    }

    @Test
    fun `internal helpers return null when nothing matches`() {
        assertNull(RecallToCards.split("no separators here"))
        assertNull(RecallToCards.cloze("tiny bit"))
    }
}
