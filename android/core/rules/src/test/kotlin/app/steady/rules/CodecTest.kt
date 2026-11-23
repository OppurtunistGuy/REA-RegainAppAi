package app.steady.rules

import app.steady.rules.serial.InstantSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecTest {

    @Test
    fun `defaults are filled on decode and written on encode`() {
        val plan = PlanCodec.decode("""{"distractions":[],"schedules":[]}""")
        assertEquals(1, plan.version)
        assertNull(plan.keyHolder)
        assertEquals(UnlockPolicy(), plan.unlockPolicy)
        val out = PlanCodec.encode(plan)
        assertTrue("\"unlockPolicy\"" in out && "\"waitSeconds\":30" in out && "\"siteBlocks\":[]" in out)
    }

    @Test
    fun `enum serial names`() {
        val p = Plan(
            distractions = listOf(Surface("app:com.x", SurfaceMode.BLOCK_APP), Surface("youtube.shorts", SurfaceMode.HIDE_SECTION)),
            schedules = emptyList(),
            keyHolder = KeyHolder("k", "Me", Relation.SELF),
        )
        val out = PlanCodec.encode(p)
        assertTrue("\"block_app\"" in out && "\"hide_section\"" in out && "\"self\"" in out)
        assertEquals(p, PlanCodec.decode(out))
    }

    @Test
    fun `unknown keys are rejected`() {
        assertFailsWith<SerializationException> {
            PlanCodec.decode("""{"distractions":[],"schedules":[],"screenText":"hello"}""")
        }
    }

    @Test
    fun `invalid schedule fields are rejected`() {
        fun sched(start: String, end: String, days: String) =
            """{"distractions":[],"schedules":[{"id":"a","label":"A","days":$days,"start":"$start","end":"$end"}]}"""
        assertFailsWith<SerializationException> { PlanCodec.decode(sched("24:00", "10:00", "[1]")) }
        assertFailsWith<SerializationException> { PlanCodec.decode(sched("09:00", "9:30", "[1]")) }
        assertFailsWith<SerializationException> { PlanCodec.decode(sched("09:00", "10:00", "[0]")) }
        assertFailsWith<SerializationException> { PlanCodec.decode(sched("09:00", "10:00", "[]")) }
        assertEquals(true, PlanCodec.decode(sched("09:00", "10:00", "[1,7]")).schedules.single().strict)
    }

    @Serializable
    private data class Holder(@Serializable(with = InstantSerializer::class) val at: Instant)

    @Test
    fun `instant serializer round trip and offsets`() {
        val h = Holder(Instant.parse("2026-11-24T10:45:00Z"))
        val s = Json.encodeToString(Holder.serializer(), h)
        assertEquals("""{"at":"2026-11-24T10:45:00Z"}""", s)
        assertEquals(h, Json.decodeFromString(Holder.serializer(), s))
        assertEquals(h, Json.decodeFromString(Holder.serializer(), """{"at":"2026-11-24T16:15:00+05:30"}"""))
        assertFailsWith<SerializationException> { Json.decodeFromString(Holder.serializer(), """{"at":"yesterday"}""") }
    }
}
