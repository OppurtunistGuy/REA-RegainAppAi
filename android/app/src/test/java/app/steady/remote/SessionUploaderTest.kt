package app.steady.remote

import app.steady.learning.FocusSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SessionUploaderTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun session(id: String, minute: Long) = FocusSession(
        id, "s1", Instant.parse("2026-11-23T10:00:00Z").plusSeconds(minute * 60),
        Instant.parse("2026-11-23T10:25:00Z").plusSeconds(minute * 60), 2, 0,
    )

    @Test fun uploadsOnlyNewSessionsOnce() = runTest {
        val acc = InMemoryAccountStore(TEST_ACCOUNT)
        val repo = MemSessionRepo()
        val up = SessionUploader(repo, apiFor(server, acc), acc)
        repeat(3) { server.enqueue(MockResponse().setResponseCode(204)) }
        assertEquals(2, up.upload(listOf(session("b", 60), session("a", 0))))
        assertEquals("a", ApiClient.json.parseToJsonElement(server.takeRequest().body.readUtf8()).let { it as kotlinx.serialization.json.JsonObject }["id"].toString().trim('"'))
        server.takeRequest()
        assertEquals(1, up.upload(listOf(session("a", 0), session("b", 60), session("c", 120))))
        assertEquals(3, server.requestCount)
        assertEquals(setOf("a", "b", "c"), acc.uploadedSessionIds())
    }

    @Test fun signedOutUploadsNothing() = runTest {
        val acc = InMemoryAccountStore()
        assertEquals(0, SessionUploader(MemSessionRepo(), apiFor(server, acc), acc).upload(listOf(session("a", 0))))
        assertEquals(0, server.requestCount)
    }

    @Test fun networkFailureRetriesLater() = runTest {
        val acc = InMemoryAccountStore(TEST_ACCOUNT)
        val up = SessionUploader(MemSessionRepo(), apiFor(server, acc), acc)
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(0, up.upload(listOf(session("a", 0), session("b", 60))))
        assertEquals(1, server.requestCount) // stopped at the first failure
        assertEquals(emptySet<String>(), acc.uploadedSessionIds())
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(2, up.upload(listOf(session("a", 0), session("b", 60))))
    }

    @Test fun rejectedSessionIsNotRetried() = runTest {
        val acc = InMemoryAccountStore(TEST_ACCOUNT)
        val up = SessionUploader(MemSessionRepo(), apiFor(server, acc), acc)
        server.enqueue(error(400, "invalid_body"))
        assertEquals(0, up.upload(listOf(session("a", 0))))
        assertEquals(setOf("a"), acc.uploadedSessionIds())
        assertEquals(0, up.upload(listOf(session("a", 0))))
        assertEquals(1, server.requestCount)
    }

    @Test fun startUploadsWhenSignedInAndOnNewSessions() = runTest {
        val acc = InMemoryAccountStore()
        val repo = MemSessionRepo(listOf(session("a", 0)))
        val up = SessionUploader(repo, apiFor(server, acc, StandardTestDispatcher(testScheduler)), acc)
        repeat(2) { server.enqueue(MockResponse().setResponseCode(204)) }
        up.start(backgroundScope)
        runCurrent()
        assertEquals(0, server.requestCount)
        acc.signIn(TEST_ACCOUNT)
        runCurrent()
        assertEquals(1, server.requestCount)
        repo.add(session("b", 60))
        runCurrent()
        assertEquals(2, server.requestCount)
    }
}
