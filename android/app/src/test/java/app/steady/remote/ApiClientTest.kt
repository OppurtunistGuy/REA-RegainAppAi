package app.steady.remote

import app.steady.learning.FocusSession
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ApiClientTest {
    private lateinit var server: MockWebServer
    private val account = InMemoryAccountStore(TEST_ACCOUNT)
    private lateinit var api: ApiClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = apiFor(server, account)
    }

    @After fun tearDown() = server.shutdown()

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName}, got $e", e)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    @Test fun getPlan200SendsBearerToken() = runTest {
        server.enqueue(json(200, plan(3).json()))
        assertEquals(plan(3), api.getPlan())
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/v1/plan", req.path)
        assertEquals("Bearer ${TEST_ACCOUNT.token}", req.getHeader("Authorization"))
    }

    @Test fun getPlan404IsNull() = runTest {
        server.enqueue(error(404, "not_found"))
        assertNull(api.getPlan())
    }

    @Test fun unauthorizedIsTyped() = runTest {
        server.enqueue(error(401, "unauthorized"))
        val e = assertThrows<ApiException.Unauthorized> { api.entitlements() }
        assertEquals("unauthorized", e.code)
    }

    @Test fun signedOutNeverSendsAuthorisedCall() = runTest {
        val signedOut = apiFor(server, InMemoryAccountStore())
        assertThrows<ApiException.Unauthorized> { signedOut.getPlan() }
        assertEquals(0, server.requestCount)
    }

    @Test fun conflictCarriesServerPlan() = runTest {
        server.enqueue(error(409, "version_conflict", ""","current":${plan(5).json()}"""))
        val e = assertThrows<ApiException.Conflict> { api.putPlan(plan(2)) }
        assertEquals("version_conflict", e.code)
        assertEquals(plan(5), e.current)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals(plan(2), app.steady.rules.PlanCodec.decode(req.body.readUtf8()))
    }

    @Test fun conflictWithNullCurrent() = runTest {
        server.enqueue(error(409, "version_conflict", ""","current":null"""))
        assertNull(assertThrows<ApiException.Conflict> { api.putPlan(plan(2)) }.current)
    }

    @Test fun notFoundAndServerAndRejected() = runTest {
        server.enqueue(error(404, "not_found"))
        assertThrows<ApiException.NotFound> { api.unlockRequest("x") }
        server.enqueue(error(500, "internal"))
        assertEquals(500, assertThrows<ApiException.Server> { api.entitlements() }.status)
        server.enqueue(error(429, "rate_limited"))
        assertEquals(429, assertThrows<ApiException.Rejected> { api.startOtp("a@b.co") }.status)
        server.enqueue(error(410, "invite_used"))
        assertEquals("invite_used", assertThrows<ApiException.Rejected> { api.acceptInvite("abcdef") }.code)
    }

    @Test fun networkFailureIsTyped() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        assertThrows<ApiException.Network> { api.getPlan() }
    }

    @Test fun badBaseUrlIsNetwork() = runTest {
        val bad = ApiClient(baseUrl = { "not a url" }, token = { "x".repeat(30) })
        assertThrows<ApiException.Network> { bad.detectors() }
    }

    @Test fun otpVerifySendsNameOnlyWhenGiven() = runTest {
        server.enqueue(json(200, """{"token":"abc","userId":"u9"}"""))
        server.enqueue(json(200, """{"token":"abc","userId":"u9"}"""))
        assertEquals(AuthResult("abc", "u9"), api.verifyOtp("a@b.co", "000000", "Riya"))
        api.verifyOtp("a@b.co", "000000", null)
        val first = Json.parseToJsonElement(server.takeRequest().also { assertEquals("/v1/auth/otp/verify", it.path); assertNull(it.getHeader("Authorization")) }.body.readUtf8()).jsonObject
        assertEquals(setOf("contact", "code", "name"), first.keys)
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(setOf("contact", "code"), second.keys)
    }

    @Test fun acceptInviteUppercasesCode() = runTest {
        server.enqueue(json(200, """{"ownerUserId":"o1","ownerName":"Riya"}"""))
        assertEquals(InviteAccepted("o1", "Riya"), api.acceptInvite(" abc234 "))
        assertEquals("/v1/keyholder/invites/ABC234/accept", server.takeRequest().path)
    }

    @Test fun inviteCreatedWith201() = runTest {
        server.enqueue(json(201, """{"code":"ABC234","expiresAt":"2026-11-24T10:00:00.000Z"}"""))
        assertEquals("ABC234", api.createInvite().code)
        assertEquals("{}", server.takeRequest().body.readUtf8())
    }

    @Test fun unlockRequestBodyKeepsNullSurface() = runTest {
        server.enqueue(json(201, """{"id":"r1","status":"pending"}"""))
        assertEquals(UnlockRequestState("r1", UnlockStatus.PENDING), api.createUnlockRequest(null, "lecture link"))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(setOf("surfaceId", "reason"), body.keys)
    }

    @Test fun sessionBodyIsAggregateOnly() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        api.postSession(FocusSession("f1", null, Instant.parse("2026-11-23T10:00:00Z"), Instant.parse("2026-11-23T10:25:00Z"), 3, 1))
        val req = server.takeRequest()
        assertEquals("/v1/sessions", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals(setOf("id", "scheduleId", "start", "end", "intercepts", "unlocks"), body.keys)
        assertEquals("\"2026-11-23T10:00:00Z\"", body["start"].toString())
    }

    @Test fun weeklySummaryPassesTimeZone() = runTest {
        server.enqueue(json(200, """{"focusMinutes":50,"blocks":2,"intercepts":4,"unlocks":0,"days":["2026-11-23"],"extra":1}"""))
        val s = api.weeklySummary(LocalDate.of(2026, 11, 23), ZoneId.of("Asia/Kolkata"))
        assertEquals(2, s.blocks)
        val path = server.takeRequest().path!!
        assertTrue(path, path.startsWith("/v1/summary/weekly?weekStart=2026-11-23&tz=Asia%2FKolkata"))
    }

    @Test fun decisionBody() = runTest {
        server.enqueue(json(200, """{"id":"r1","status":"approved","until":"2026-11-23T10:15:00.000Z"}"""))
        val s = api.decide("r1", approve = true, minutes = 15)
        assertEquals(UnlockStatus.APPROVED, s.status)
        assertEquals("""{"approve":true,"minutes":15}""", server.takeRequest().body.readUtf8())
    }

    @Test fun validBaseUrls() {
        assertTrue(ApiClient.isValidBaseUrl(AccountStore.DEFAULT_BASE_URL))
        assertTrue(!ApiClient.isValidBaseUrl("ftp://x"))
        assertTrue(!ApiClient.isValidBaseUrl(""))
    }
}
