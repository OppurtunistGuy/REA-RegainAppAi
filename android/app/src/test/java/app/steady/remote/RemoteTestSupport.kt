package app.steady.remote

import app.steady.data.PlanRepository
import app.steady.data.SessionRepository
import app.steady.data.UnlockRepository
import app.steady.learning.FocusSession
import app.steady.rules.Plan
import app.steady.rules.PlanCodec
import app.steady.rules.Schedule
import app.steady.rules.Surface
import app.steady.rules.SurfaceMode
import app.steady.rules.UnlockGrant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.Instant
import java.time.LocalDate

val TEST_ACCOUNT = Account(userId = "u1", token = "t".repeat(32), name = "Riya", contact = "riya@example.com")

fun apiFor(
    server: MockWebServer,
    account: AccountStore,
    io: CoroutineDispatcher = Dispatchers.IO,
) = ApiClient(
    baseUrl = { server.url("/").toString() },
    token = { account.account.value?.token },
    io = io,
)

fun json(code: Int, body: String) = MockResponse().setResponseCode(code)
    .setHeader("Content-Type", "application/json").setBody(body)

fun error(code: Int, errCode: String, extra: String = "") =
    json(code, """{"error":{"code":"$errCode","message":"m"}$extra}""")

fun plan(version: Int, label: String = "Study") = Plan(
    version = version,
    distractions = listOf(Surface("instagram.reels", SurfaceMode.HIDE_SECTION)),
    schedules = listOf(Schedule("s1", label, setOf(1, 2, 3), "16:00", "18:00")),
)

fun Plan.json(): String = PlanCodec.encode(this)

class MemPlanRepo(initial: Plan) : PlanRepository {
    val current = MutableStateFlow(initial)
    val saved = mutableListOf<Plan>()
    override val plan: Flow<Plan> = current
    override suspend fun save(plan: Plan) {
        saved += plan
        current.value = plan
    }
}

class MemUnlockRepo : UnlockRepository {
    val state = MutableStateFlow<List<UnlockGrant>>(emptyList())
    var localRequests = 0
    override val grants: Flow<List<UnlockGrant>> = state
    override suspend fun add(g: UnlockGrant) = state.update { it + g }
    override suspend fun requestFromKeyHolder(surfaceId: String?, reason: String): String {
        localRequests++
        return "local-$localRequests"
    }
}

class MemSessionRepo(initial: List<FocusSession> = emptyList()) : SessionRepository {
    val state = MutableStateFlow(initial)
    override val sessions: Flow<List<FocusSession>> = state
    override suspend fun add(s: FocusSession) = state.update { it + s }
    override suspend fun recordIntercept(surfaceId: String, at: Instant) = Unit
    override fun interceptsOn(date: LocalDate): Flow<Int> = flowOf(0)
}

class FakeBillingGateway(initial: List<OwnedPurchase> = emptyList()) : BillingGateway {
    val state = MutableStateFlow(initial)
    val acknowledged = mutableListOf<String>()
    var offersList = emptyList<ProductOffer>()
    var refreshes = 0
    var available = true
    override val purchases: StateFlow<List<OwnedPurchase>> = state
    override suspend fun refreshPurchases(): Boolean { refreshes++; return available }
    override suspend fun acknowledge(purchaseToken: String): Boolean {
        acknowledged += purchaseToken
        state.update { l -> l.map { if (it.purchaseToken == purchaseToken) it.copy(acknowledged = true) else it } }
        return true
    }
    override suspend fun offers(): List<ProductOffer> = offersList
    override suspend fun launchPurchase(activity: android.app.Activity, productId: String) = available
}

fun purchase(
    product: String = PlusProducts.MONTHLY,
    token: String = "tok",
    purchased: Boolean = true,
    acknowledged: Boolean = true,
) = OwnedPurchase(listOf(product), token, purchased, acknowledged, autoRenewing = true)
