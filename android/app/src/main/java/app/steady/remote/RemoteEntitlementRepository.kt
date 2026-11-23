package app.steady.remote

import app.steady.data.Entitlement
import app.steady.data.EntitlementRepository
import app.steady.data.Tier
import app.steady.feature.paywall.FreeTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Pure entitlement rules. Free is the default; Plus needs an owned Play purchase or the server's word. */
object EntitlementLogic {
    val FREE = Entitlement(Tier.FREE, emptySet())
    val PLUS_FEATURES: Set<String> get() = FreeTier.plusFeatures

    /** An active Plus purchase: a Plus product in PURCHASED state (pending purchases don't count). */
    fun activePlus(purchases: List<OwnedPurchase>): OwnedPurchase? =
        purchases.firstOrNull { p -> p.purchased && p.productIds.any { it in PlusProducts.ALL } }

    /**
     * @param server the backend's answer, or null when signed out / offline / not fetched yet.
     */
    fun combine(purchases: List<OwnedPurchase>, server: ServerEntitlement?): Entitlement {
        val serverPlus = server?.tier == "plus"
        val billingPlus = activePlus(purchases) != null
        if (!serverPlus && !billingPlus) return FREE
        val features = PLUS_FEATURES + (if (serverPlus) server!!.features else emptyList())
        return Entitlement(Tier.PLUS, features)
    }

    /** Purchases that still need acknowledging (Play refunds them after 3 days otherwise). */
    fun needsAck(purchases: List<OwnedPurchase>): List<OwnedPurchase> =
        purchases.filter { it.purchased && !it.acknowledged && it.productIds.any { id -> id in PlusProducts.ALL } }
}

/**
 * Remote-backed [EntitlementRepository]: Play Billing purchases combined with `GET /v1/entitlements`.
 * Free by default, while loading, when offline and when signed out with no purchase.
 */
class RemoteEntitlementRepository(
    private val billing: BillingGateway,
    private val api: ApiClient,
    private val account: AccountStore,
    private val scope: CoroutineScope,
) : EntitlementRepository {
    private val server = MutableStateFlow<ServerEntitlement?>(null)

    override val entitlement: StateFlow<Entitlement> =
        combine(billing.purchases, server) { p, s -> EntitlementLogic.combine(p, s) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, EntitlementLogic.FREE)

    /** The purchase behind Plus, for the "Manage subscription" link. */
    val activePurchase: StateFlow<OwnedPurchase?> = billing.purchases
        .map { EntitlementLogic.activePlus(it) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Reloads purchases (acknowledging new ones) and the server entitlement. */
    suspend fun refresh() {
        billing.refreshPurchases()
        acknowledgePending()
        refreshServer()
    }

    suspend fun refreshServer() {
        server.value = if (!account.isSignedIn) null else try {
            api.entitlements()
        } catch (e: ApiException) {
            null // offline or rejected → free unless Play says otherwise
        }
    }

    private suspend fun acknowledgePending() {
        EntitlementLogic.needsAck(billing.purchases.value).forEach { billing.acknowledge(it.purchaseToken) }
    }

    /** Acknowledge purchases as they arrive; refetch the server tier when the account changes. */
    fun start(): Job = scope.launch {
        launch { billing.purchases.collect { acknowledgePending() } }
        launch { account.account.map { it?.userId }.distinctUntilChanged().collect { refreshServer() } }
        launch { billing.refreshPurchases() }
    }
}
