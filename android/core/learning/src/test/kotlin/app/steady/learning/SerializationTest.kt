package app.steady.learning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class SerializationTest {
    private val json = Json

    @Test
    fun `Card round-trips through JSON with ISO instants`() {
        val created = Instant.parse("2026-11-23T10:15:30.123Z")
        val card = Card(
            "c1", "physics", "Parallel axis", "I = I_cm + Md²", created,
            ReviewState(Instant.parse("2026-11-24T10:15:30Z"), 1.0, 5.0, 1, 0),
        )
        val text = json.encodeToString(Card.serializer(), card)
        val obj = json.parseToJsonElement(text).jsonObject
        assertEquals("2026-11-23T10:15:30.123Z", obj["createdAt"]!!.jsonPrimitive.content)
        assertEquals("2026-11-24T10:15:30Z", obj["state"]!!.jsonObject["due"]!!.jsonPrimitive.content)
        assertEquals(card, json.decodeFromString(Card.serializer(), text))
    }

    @Test
    fun `FocusSession round-trips including null scheduleId`() {
        val s = FocusSession("s1", null, Instant.parse("2026-11-23T09:00:00Z"), Instant.parse("2026-11-23T09:25:00Z"), 3, 1)
        assertEquals(s, json.decodeFromString(FocusSession.serializer(), json.encodeToString(FocusSession.serializer(), s)))
    }
}
