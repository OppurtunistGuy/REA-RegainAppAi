package app.steady.remote

import app.steady.data.Entitlement
import app.steady.data.Features
import app.steady.data.Tier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class EntitlementLogicTest {
    private val plusServer = ServerEntitlement("plus", listOf(Features.UNLIMITED_SCHEDULES, "institute_reports"))

    @Test fun freeByDefault() = assertEquals(EntitlementLogic.FREE, EntitlementLogic.combine(emptyList(), null))

    @Test fun freeServerNoPurchase() =
        assertEquals(EntitlementLogic.FREE, EntitlementLogic.combine(emptyList(), ServerEntitlement("free")))

    @Test fun purchaseAloneGivesPlus() {
        val e = EntitlementLogic.combine(listOf(purchase(PlusProducts.YEARLY)), null)
        assertEquals(Tier.PLUS, e.tier)
        assertEquals(EntitlementLogic.PLUS_FEATURES, e.features)
    }

    @Test fun pendingPurchaseIsNotPlus() =
        assertEquals(EntitlementLogic.FREE, EntitlementLogic.combine(listOf(purchase(purchased = false)), null))

    @Test fun unknownProductIsNotPlus() =
        assertEquals(EntitlementLogic.FREE, EntitlementLogic.combine(listOf(purchase("other_product")), null))

    @Test fun serverPlusAddsServerFeatures() {
        val e = EntitlementLogic.combine(emptyList(), plusServer)
        assertEquals(Entitlement(Tier.PLUS, EntitlementLogic.PLUS_FEATURES + "institute_reports"), e)
    }

    @Test fun needsAckOnlyPurchasedUnacknowledgedPlus() {
        val list = listOf(
            purchase(token = "a", acknowledged = false),
            purchase(token = "b", acknowledged = true),
            purchase(token = "c", acknowledged = false, purchased = false),
            purchase(product = "x", token = "d", acknowledged = false),
        )
        assertEquals(listOf("a"), EntitlementLogic.needsAck(list).map { it.purchaseToken })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteEntitlementRepositoryTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    @Test fun signedOutOfflineIsFreeWithoutCallingServer() = runTest {
        val acc = InMemoryAccountStore()
        val billing = FakeBillingGateway()
        val repo = RemoteEntitlementRepository(billing, apiFor(server, acc, StandardTestDispatcher(testScheduler)), acc, backgroundScope)
        repo.start()
        runCurrent()
        assertEquals(EntitlementLogic.FREE, repo.entitlement.value)
        assertEquals(0, server.requestCount)
        assertEquals(1, billing.refreshes)
    }

    @Test fun serverPlusWhenSignedIn() = runTest {
        server.enqueue(json(200, """{"tier":"plus","features":["unlimited_schedules","desktop_sync","notes_to_cards","task_breakdown"]}"""))
        val acc = InMemoryAccountStore(TEST_ACCOUNT)
        val repo = RemoteEntitlementRepository(FakeBillingGateway(), apiFor(server, acc, StandardTestDispatcher(testScheduler)), acc, backgroundScope)
        repo.start()
        runCurrent()
        assertEquals(Tier.PLUS, repo.entitlement.value.tier)
        assertEquals("/v1/entitlements", server.takeRequest().path)
    }

    @Test fun serverErrorFallsBackToFree() = runTest {
        server.enqueue(error(500, "internal"))
        val acc = InMemoryAccountStore(TEST_ACCOUNT)
        val repo = RemoteEntitlementRepository(FakeBillingGateway(), apiFor(server, acc, StandardTestDispatcher(testScheduler)), acc, backgroundScope)
        repo.start()
        runCurrent()
        assertEquals(EntitlementLogic.FREE, repo.entitlement.value)
    }

    @Test fun newPurchaseIsAcknowledgedAndGivesPlus() = runTest {
        val acc = InMemoryAccountStore()
        val billing = FakeBillingGateway()
        val repo = RemoteEntitlementRepository(billing, apiFor(server, acc, StandardTestDispatcher(testScheduler)), acc, backgroundScope)
        repo.start()
        runCurrent()
        billing.state.value = listOf(purchase(token = "p1", acknowledged = false))
        runCurrent()
        assertEquals(listOf("p1"), billing.acknowledged)
        assertEquals(Tier.PLUS, repo.entitlement.value.tier)
        assertEquals("p1", repo.activePurchase.value?.purchaseToken)
    }

    @Test fun signOutDropsServerPlus() = runTest {
        server.enqueue(json(200, """{"tier":"plus","features":[]}"""))
        val acc = InMemoryAccountStore(TEST_ACCOUNT)
        val repo = RemoteEntitlementRepository(FakeBillingGateway(), apiFor(server, acc, StandardTestDispatcher(testScheduler)), acc, backgroundScope)
        repo.start()
        runCurrent()
        assertEquals(Tier.PLUS, repo.entitlement.value.tier)
        acc.signOut()
        runCurrent()
        assertEquals(EntitlementLogic.FREE, repo.entitlement.value)
        assertNull(repo.activePurchase.value)
    }
}
