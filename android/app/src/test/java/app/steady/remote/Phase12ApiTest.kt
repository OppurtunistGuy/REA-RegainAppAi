package app.steady.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Client side of the "Phase 12 additions" endpoints in docs/CONTRACTS.md. */
class Phase12ApiTest {
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

    @Test fun logoutSendsGivenToken() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        api.logout("old-token")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/auth/logout", req.path)
        assertEquals("Bearer old-token", req.getHeader("Authorization"))
    }

    @Test fun deleteMe() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        api.deleteMe()
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/v1/me", req.path)
        assertEquals("Bearer ${TEST_ACCOUNT.token}", req.getHeader("Authorization"))
    }

    @Test fun keyHolderLinksAndOwners() = runTest {
        server.enqueue(json(200, """[{"linkId":"l1","keyHolderUserId":"k1","keyHolderName":"Mum","createdAt":"2026-11-01T10:00:00Z","x":1}]"""))
        server.enqueue(json(200, """[{"linkId":"l2","ownerUserId":"o1","ownerName":"Asha","createdAt":"2026-11-02T10:00:00Z"}]"""))
        assertEquals(listOf(KeyHolderLink("l1", "k1", "Mum", "2026-11-01T10:00:00Z")), api.keyHolderLinks())
        assertEquals(listOf(OwnerLink("l2", "o1", "Asha", "2026-11-02T10:00:00Z")), api.keyHolderOwners())
        assertEquals("/v1/keyholder/links", server.takeRequest().path)
        assertEquals("/v1/keyholder/owners", server.takeRequest().path)
    }

    @Test fun removeLink() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(error(404, "not_found"))
        api.removeKeyHolderLink("l1")
        assertThrows<ApiException.NotFound> { api.removeKeyHolderLink("l1") }
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/v1/keyholder/links/l1", req.path)
    }

    @Test fun ownerSummaryQuery() = runTest {
        server.enqueue(json(200, """{"focusMinutes":180,"blocks":4,"intercepts":9,"unlocks":1,"days":["2026-11-23"]}"""))
        val s = api.ownerSummary("o1", LocalDate.of(2026, 11, 23), ZoneId.of("Asia/Kolkata"))
        assertEquals(4, s.blocks)
        assertEquals(180, s.focusMinutes)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/v1/keyholder/summary", url.encodedPath)
        assertEquals("o1", url.queryParameter("ownerUserId"))
        assertEquals("2026-11-23", url.queryParameter("weekStart"))
        assertEquals("Asia/Kolkata", url.queryParameter("tz"))
    }

    @Test fun billingVerifyBody() = runTest {
        server.enqueue(json(200, """{"tier":"plus","features":["unlimited_schedules"]}"""))
        val e = api.verifyPurchase(PlusProducts.MONTHLY, "test-abc")
        assertEquals("plus", e.tier)
        val req = server.takeRequest()
        assertEquals("/v1/billing/verify", req.path)
        assertEquals("""{"productId":"steady_plus_monthly","purchaseToken":"test-abc"}""", req.body.readUtf8())
    }

    @Test fun keyholderLimitIsTyped() = runTest {
        server.enqueue(error(409, "keyholder_limit"))
        val e = assertThrows<ApiException.Conflict> { api.acceptInvite("abc123") }
        assertTrue(e.isKeyHolderLimit)
        assertEquals(
            app.steady.R.string.partner_err_keyholder_limit,
            app.steady.feature.settings.accountErrorText(e),
        )
    }

    @Test fun tokenExpiredSignsOutThroughGuard() = runTest {
        val store = InMemoryAccountStore(TEST_ACCOUNT)
        val guard = AuthGuard(store)
        val guarded = ApiClient(
            baseUrl = { server.url("/").toString() },
            token = { store.account.value?.token },
            onUnauthorized = guard::onUnauthorized,
        )
        server.enqueue(error(401, "token_expired"))
        val e = assertThrows<ApiException.Unauthorized> { guarded.entitlements() }
        assertTrue(e.isExpired)
        assertNull(store.account.value)
        assertEquals(SignedOutReason.EXPIRED, guard.reason.value)
        guard.clear()
        assertNull(guard.reason.value)
    }

    @Test fun guardIgnoresRejectionOfOldToken() {
        val store = InMemoryAccountStore(TEST_ACCOUNT)
        val guard = AuthGuard(store)
        guard.onUnauthorized("some-older-token", ApiException.Unauthorized("token_expired"))
        assertEquals(TEST_ACCOUNT, store.account.value)
        assertNull(guard.reason.value)
        guard.onUnauthorized(TEST_ACCOUNT.token, ApiException.Unauthorized("unauthorized"))
        assertEquals(SignedOutReason.REJECTED, guard.reason.value)
    }

    @Test fun invalidOtpCodeDoesNotSignOut() = runTest {
        val store = InMemoryAccountStore(TEST_ACCOUNT)
        val guard = AuthGuard(store)
        val guarded = ApiClient({ server.url("/").toString() }, { store.account.value?.token }, onUnauthorized = guard::onUnauthorized)
        server.enqueue(error(401, "invalid_code"))
        assertThrows<ApiException.Unauthorized> { guarded.verifyOtp("a@b.c", "000000", null) }
        assertEquals(TEST_ACCOUNT, store.account.value)
    }

    @Test fun purchaseIsVerifiedOnceWhenSignedIn() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val billing = FakeBillingGateway(listOf(purchase(token = "test-p1")))
        val store = InMemoryAccountStore(TEST_ACCOUNT)
        val repo = RemoteEntitlementRepository(billing, apiFor(server, store, dispatcher), store, scope)
        server.enqueue(json(200, """{"tier":"plus","features":["desktop_sync"]}"""))
        assertTrue(repo.verifyPurchases())
        assertFalse(repo.verifyPurchases()) // already verified: no second request
        assertEquals(1, server.requestCount)
        assertEquals("/v1/billing/verify", server.takeRequest().path)
        assertTrue("desktop_sync" in repo.entitlement.value.features)
        scope.cancel()
    }

    @Test fun billingNotConfiguredKeepsPlayPlus() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val billing = FakeBillingGateway(listOf(purchase(token = "real-token")))
        val store = InMemoryAccountStore(TEST_ACCOUNT)
        val repo = RemoteEntitlementRepository(billing, apiFor(server, store, dispatcher), store, scope)
        server.enqueue(error(503, "billing_not_configured"))
        assertFalse(repo.verifyPurchases())
        assertEquals(app.steady.data.Tier.PLUS, repo.entitlement.value.tier)
        scope.cancel()
    }

    @Test fun signedOutNeverVerifies() = runTest {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val repo = RemoteEntitlementRepository(FakeBillingGateway(listOf(purchase())), api, InMemoryAccountStore(), scope)
        assertFalse(repo.verifyPurchases())
        assertEquals(0, server.requestCount)
        scope.cancel()
    }
}
