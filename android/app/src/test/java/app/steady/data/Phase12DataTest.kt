package app.steady.data

import app.steady.data.db.SteadyDatabase
import app.steady.data.local.LocalCardRepository
import app.steady.data.local.LocalSessionRepository
import app.steady.detect.Detector
import app.steady.detect.DetectorConfig
import app.steady.detect.Matcher
import app.steady.di.ZoneAwareRules
import app.steady.learning.Card
import app.steady.learning.ReviewState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class Phase12DataTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant()

    // ---- Room schema / migration ----

    private fun schema(version: Int): File =
        listOf("schemas", "app/schemas").map { File(it, "app.steady.data.db.SteadyDatabase/$version.json") }.first { it.exists() }

    @Test fun migration1to2CreatesExactlyTheExportedIndex() {
        assertEquals(2, SteadyDatabase.VERSION)
        val db = Json.parseToJsonElement(schema(2).readText()).jsonObject["database"]!!.jsonObject
        assertEquals(2, db["version"]!!.jsonPrimitive.content.toInt())
        val table = db["entities"]!!.jsonArray.map { it.jsonObject }.first { it["tableName"]!!.jsonPrimitive.content == "intercept_events" }
        val sql = table["indices"]!!.jsonArray.map { it.jsonObject }
            .first { it["name"]!!.jsonPrimitive.content == "index_intercept_events_atMs_surfaceId" }["createSql"]!!.jsonPrimitive.content
            .replace("\${TABLE_NAME}", "intercept_events")
        assertEquals(sql, SteadyDatabase.CREATE_INTERCEPT_SURFACE_INDEX)
        assertEquals(listOf(1 to 2), SteadyDatabase.MIGRATIONS.map { it.startVersion to it.endVersion })
    }

    @Test fun schemaV1StillExportedAndOnlyIndexDiffers() {
        fun tables(v: Int) = Json.parseToJsonElement(schema(v).readText()).jsonObject["database"]!!.jsonObject["entities"]!!
            .jsonArray.associate { it.jsonObject["tableName"]!!.jsonPrimitive.content to it.jsonObject["createSql"]!!.jsonPrimitive.content }
        assertEquals(tables(1), tables(2)) // same columns: the migration needs no table rebuild
    }

    // ---- repositories ----

    @Test fun interceptsBySurfaceGroupsWithinRange() = runTest {
        val dao = FakeSessionDao()
        val repo = LocalSessionRepository(dao, zone)
        val day = LocalDate.of(2026, 11, 24)
        repo.recordIntercept("instagram.reels", at(day, 9))
        repo.recordIntercept("instagram.reels", at(day, 23))
        repo.recordIntercept("youtube.shorts", at(day.minusDays(1), 12))
        repo.recordIntercept("youtube.shorts", at(day.plusDays(1), 0)) // outside [from, to)
        val counts = repo.interceptsBySurface(day.minusDays(1), day.plusDays(1)).first()
        assertEquals(mapOf("instagram.reels" to 2, "youtube.shorts" to 1), counts)
    }

    @Test fun sessionRepoFollowsZoneChanges() = runTest {
        var current = ZoneId.of("UTC")
        val repo = LocalSessionRepository(FakeSessionDao()) { current }
        val day = LocalDate.of(2026, 11, 24)
        repo.recordIntercept("instagram.reels", Instant.parse("2026-11-24T20:00:00Z")) // 25th 01:30 in Kolkata
        assertEquals(1, repo.interceptsOn(day).first())
        current = zone
        assertEquals(0, repo.interceptsOn(day).first())
    }

    @Test fun cardDeleteRemovesOnlyThoseIds() = runTest {
        val dao = FakeCardDao()
        val repo = LocalCardRepository(dao)
        val t = Instant.parse("2026-11-24T10:00:00Z")
        val cards = (1..3).map { Card("c$it", "d", "f$it", "b$it", t, ReviewState(t, 0.0, 5.0, 0, 0)) }
        repo.upsert(cards)
        repo.delete(listOf("c1", "c3", "nope"))
        assertEquals(listOf("c2"), repo.cards.first().map { it.id })
        repo.delete(emptyList())
        assertEquals(1, dao.deleteCalls.size)
    }

    // ---- zone-aware rules ----

    @Test fun rulesRebuiltOnlyWhenZoneChanges() {
        var z = ZoneId.of("UTC")
        val rules = ZoneAwareRules { z }
        val first = rules.get()
        assertSame(first, rules.get())
        z = zone
        assertNotSame(first, rules.get())
    }

    // ---- detector config store ----

    private class MemPersistence(var text: String? = null) : DetectorConfigPersistence {
        var writes = 0
        override fun read() = text
        override fun write(json: String) { text = json; writes++ }
    }

    private fun config(v: Int) = DetectorConfig(v, listOf(Detector("instagram.reels", "com.instagram.android", listOf(Matcher(viewIdEndsWith = "clips_tab")))))

    private class FakeSync(var result: DetectorConfig?, val fail: Boolean = false) : SyncApi {
        override suspend fun pushPlan(plan: app.steady.rules.Plan) = plan
        override suspend fun pullPlan(): app.steady.rules.Plan? = null
        override suspend fun detectors(): DetectorConfig? = if (fail) throw IllegalStateException("offline") else result
    }

    private val noLog: (String, Throwable?) -> Unit = { _, _ -> }

    @Test fun storeStartsFromSeedAndKeepsHigherVersion() = runTest {
        val p = MemPersistence()
        val store = DetectorConfigStore({ config(1) }, p, noLog)
        assertEquals(1, store.current.value.version)
        assertFalse(store.refresh(FakeSync(config(1))))
        assertFalse(store.refresh(FakeSync(null, fail = true)))
        assertTrue(store.refresh(FakeSync(config(3))))
        assertEquals(3, store.current.value.version)
        assertFalse(store.offer(config(2)))
        assertEquals(3, store.current.value.version)
        assertEquals(1, p.writes)
        // Next run: the persisted v3 beats the v1 asset.
        assertEquals(3, DetectorConfigStore({ config(1) }, p, noLog).current.value.version)
        // A newer asset (app update) beats an older download.
        assertEquals(5, DetectorConfigStore({ config(5) }, p, noLog).current.value.version)
    }

    @Test fun storeIgnoresCorruptOrEmpty() = runTest {
        val store = DetectorConfigStore({ config(1) }, MemPersistence("{not json"), noLog)
        assertEquals(1, store.current.value.version)
        assertFalse(store.offer(DetectorConfig(9, emptyList())))
        assertEquals(1, store.current.value.version)
    }
}
