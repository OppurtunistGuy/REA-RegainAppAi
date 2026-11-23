package app.steady.learning

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SwapPickerTest {
    private val now = Instant.parse("2026-11-23T12:00:00Z")

    private fun card(id: String, created: String, due: String) = Card(
        id, "d", "f", "b", Instant.parse(created),
        Scheduler.newState(Instant.parse(due)),
    )

    @Test
    fun `empty list gives null`() {
        assertNull(SwapPicker.pick(emptyList(), now))
    }

    @Test
    fun `most overdue card first`() {
        val cards = listOf(
            card("a", "2026-11-20T00:00:00Z", "2026-11-23T11:00:00Z"),
            card("b", "2026-11-19T00:00:00Z", "2026-11-21T00:00:00Z"),
            card("c", "2026-11-22T00:00:00Z", "2026-11-30T00:00:00Z"),
        )
        assertEquals("b", SwapPicker.pick(cards, now)?.id)
    }

    @Test
    fun `card due exactly now counts as due`() {
        val cards = listOf(
            card("exact", "2026-11-01T00:00:00Z", "2026-11-23T12:00:00Z"),
            card("newer", "2026-11-22T00:00:00Z", "2026-12-01T00:00:00Z"),
        )
        assertEquals("exact", SwapPicker.pick(cards, now)?.id)
    }

    @Test
    fun `ties on due prefer the newest card`() {
        val cards = listOf(
            card("old", "2026-11-01T00:00:00Z", "2026-11-22T00:00:00Z"),
            card("new", "2026-11-10T00:00:00Z", "2026-11-22T00:00:00Z"),
        )
        assertEquals("new", SwapPicker.pick(cards, now)?.id)
    }

    @Test
    fun `nothing due gives the newest card`() {
        val cards = listOf(
            card("a", "2026-11-20T00:00:00Z", "2026-11-25T00:00:00Z"),
            card("b", "2026-11-22T00:00:00Z", "2026-11-30T00:00:00Z"),
            card("c", "2026-11-21T00:00:00Z", "2026-11-24T00:00:00Z"),
        )
        assertEquals("b", SwapPicker.pick(cards, now)?.id)
    }
}
