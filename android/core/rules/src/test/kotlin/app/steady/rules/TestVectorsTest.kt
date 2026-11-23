package app.steady.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs every case in `shared/schema/test-vectors.json` (path passed in via the `vectors` system property). */
class TestVectorsTest {

    private val root: JsonObject by lazy {
        val path = System.getProperty("vectors") ?: error("system property 'vectors' not set")
        Json.parseToJsonElement(File(path).readText()).jsonObject
    }
    private val plan: Plan by lazy { PlanCodec.decode(root.getValue("plan").toString()) }
    private val engine: RulesEngine by lazy { RulesEngine(ZoneId.of(root.getValue("zone").jsonPrimitive.content)) }
    private val cases: List<JsonObject> by lazy { root.getValue("cases").jsonArray.map { it.jsonObject } }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun `vector file has cases`() {
        assertTrue(cases.isNotEmpty())
    }

    @TestFactory
    fun state(): List<DynamicTest> = cases.map { c ->
        DynamicTest.dynamicTest("state: ${c.str("name")}") {
            val now = Instant.parse(c.str("now"))
            val state = engine.state(plan, now)
            when (c.str("state")) {
                "active" -> {
                    val active = assertIs<BlockState.Active>(state)
                    assertEquals(c.str("scheduleId"), active.scheduleId)
                    c.str("endsAt")?.let { assertEquals(Instant.parse(it), active.endsAt) }
                    assertTrue(!now.isBefore(active.startedAt) && now.isBefore(active.endsAt))
                    assertEquals(plan.schedules.first { it.id == active.scheduleId }.strict, active.strict)
                }
                "idle" -> assertEquals(BlockState.Idle, state)
                else -> error("unknown state ${c.str("state")}")
            }
        }
    }

    @TestFactory
    fun nextBlock(): List<DynamicTest> = cases.filter { it.str("nextScheduleId") != null }.map { c ->
        DynamicTest.dynamicTest("nextBlock: ${c.str("name")}") {
            val now = Instant.parse(c.str("now"))
            val next = assertNotNull(engine.nextBlock(plan, now))
            assertEquals(c.str("nextScheduleId"), next.scheduleId)
            c.str("nextStartsAt")?.let { assertEquals(Instant.parse(it), next.startsAt) }
            assertTrue(next.startsAt.isAfter(now))
        }
    }

    @TestFactory
    fun nextBlockAlwaysStrictlyAfterNow(): List<DynamicTest> = cases.map { c ->
        DynamicTest.dynamicTest("nextBlock > now: ${c.str("name")}") {
            val now = Instant.parse(c.str("now"))
            val next = assertNotNull(engine.nextBlock(plan, now))
            assertTrue(next.startsAt.isAfter(now))
            assertTrue(next.endsAt.isAfter(next.startsAt))
        }
    }

    @Test
    fun `codec round-trips the vector plan`() {
        val encoded = PlanCodec.encode(plan)
        assertEquals(plan, PlanCodec.decode(encoded))
        // Also equal, as a JSON tree, to the source (all fields present there).
        assertEquals(root.getValue("plan"), Json.parseToJsonElement(encoded))
    }
}
