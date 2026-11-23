package app.steady.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteUnlockRepositoryTest {
    private lateinit var server: MockWebServer
    private val polls = AtomicInteger(0)
    /** Status returned by the n-th poll (1-based); default pending. */
    @Volatile private var pollAnswer: (Int) -> MockResponse = { json(200, """{"id":"r1","status":"pending"}""") }
    @Volatile private var createAnswer: MockResponse = json(201, """{"id":"r1","status":"pending"}""")

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "POST" && request.path == "/v1/unlock-requests" -> createAnswer
                    request.method == "GET" && request.path == "/v1/unlock-requests/r1" -> pollAnswer(polls.incrementAndGet())
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
    }

    @After fun tearDown() = server.shutdown()

    private val now = Instant.parse("2026-11-23T10:00:00Z")

    private fun TestScope.repo(local: MemUnlockRepo, account: AccountStore = InMemoryAccountStore(TEST_ACCOUNT)) =
        RemoteUnlockRepository(
            local = local,
            api = apiFor(server, account, StandardTestDispatcher(testScheduler)),
            account = account,
            scope = backgroundScope,
            keyHolderId = { "kh-mom" },
            now = { now },
        )

    @Test fun approvedAddsGrantFromKeyHolder() = runTest {
        pollAnswer = { n ->
            if (n < 3) json(200, """{"id":"r1","status":"pending"}""")
            else json(200, """{"id":"r1","status":"approved","until":"2026-11-23T10:15:00.000Z"}""")
        }
        val local = MemUnlockRepo()
        val r = repo(local)
        assertEquals("r1", r.requestFromKeyHolder("youtube.shorts", "  lecture link  "))
        assertEquals(UnlockOutcome.Pending, r.outcomes.value["r1"])
        assertTrue(server.takeRequest().body.readUtf8().contains("\"reason\":\"lecture link\""))

        advanceTimeBy(20_001); runCurrent()
        assertEquals(2, polls.get())
        assertTrue(local.state.value.isEmpty())
        advanceTimeBy(10_000); runCurrent()
        assertEquals(3, polls.get())

        val grant = local.state.value.single()
        assertEquals("kh-mom", grant.approvedBy)
        assertEquals("youtube.shorts", grant.surfaceId)
        assertEquals(Instant.parse("2026-11-23T10:15:00Z"), grant.until)
        assertEquals(now, grant.from)
        assertEquals(UnlockOutcome.Approved(grant), r.outcomes.value["r1"])
        assertEquals(0, local.localRequests)
    }

    @Test fun deniedAddsNoGrant() = runTest {
        pollAnswer = { json(200, """{"id":"r1","status":"denied"}""") }
        val local = MemUnlockRepo()
        val r = repo(local)
        r.requestFromKeyHolder(null, "reason here")
        advanceTimeBy(10_001); runCurrent()
        assertEquals(UnlockOutcome.Denied, r.outcomes.value["r1"])
        assertEquals(1, polls.get())
        assertTrue(local.state.value.isEmpty())
    }

    @Test fun timesOutAfterFifteenMinutes() = runTest {
        val local = MemUnlockRepo()
        val r = repo(local)
        val start = currentTime
        assertEquals(UnlockOutcome.TimedOut, r.awaitDecision("r1", null))
        assertEquals(15 * 60_000L, currentTime - start)
        assertEquals(90, polls.get()) // every 10 s for 15 min
        assertTrue(local.state.value.isEmpty())
    }

    @Test fun networkBlipKeepsPolling() = runTest {
        pollAnswer = { n ->
            if (n == 1) MockResponse().setResponseCode(503)
            else json(200, """{"id":"r1","status":"approved","until":"2026-11-23T10:15:00Z"}""")
        }
        val local = MemUnlockRepo()
        val out = repo(local).awaitDecision("r1", null)
        assertTrue(out is UnlockOutcome.Approved)
        assertEquals(2, polls.get())
    }

    @Test fun missingRequestAbandons() = runTest {
        pollAnswer = { error(404, "not_found") }
        assertEquals(UnlockOutcome.Abandoned, repo(MemUnlockRepo()).awaitDecision("r1", null))
    }

    @Test fun signedOutThrowsNotSignedIn() = runTest {
        val local = MemUnlockRepo()
        try {
            repo(local, InMemoryAccountStore()).requestFromKeyHolder(null, "need it")
            fail("expected NotSignedIn")
        } catch (_: NotSignedIn) {
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun noKeyHolderLinked() = runTest {
        createAnswer = error(409, "no_keyholder")
        try {
            repo(MemUnlockRepo()).requestFromKeyHolder(null, "need it")
            fail("expected NoKeyHolderLinked")
        } catch (_: NoKeyHolderLinked) {
        }
    }

    @Test fun grantsDelegateToLocal() = runTest {
        val local = MemUnlockRepo()
        val r = repo(local)
        val g = app.steady.rules.UnlockGrant("g", null, now, now.plusSeconds(60), null)
        r.add(g)
        assertEquals(listOf(g), local.state.value)
    }
}
