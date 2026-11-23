package app.steady.remote

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/** Play product ids for Steady Plus (subscriptions). */
object PlusProducts {
    const val MONTHLY = "steady_plus_monthly"
    const val YEARLY = "steady_plus_yearly"
    val ALL = listOf(MONTHLY, YEARLY)
}

/** A purchase as far as entitlement logic cares. No order ids or account data. */
data class OwnedPurchase(
    val productIds: List<String>,
    val purchaseToken: String,
    val purchased: Boolean,       // PurchaseState.PURCHASED (not PENDING)
    val acknowledged: Boolean,
    val autoRenewing: Boolean,
)

/** Price shown on the paywall, from Play product details. */
data class ProductOffer(val productId: String, val formattedPrice: String, val offerToken: String?)

/**
 * The Play Billing surface Steady needs, so JVM tests can use a fake.
 * Implemented by [PlayBillingGateway].
 */
interface BillingGateway {
    /** Latest known purchases; empty until [refreshPurchases] succeeds. */
    val purchases: StateFlow<List<OwnedPurchase>>
    /** Connects if needed and reloads owned subscriptions. Returns false if Play is unavailable. */
    suspend fun refreshPurchases(): Boolean
    suspend fun acknowledge(purchaseToken: String): Boolean
    /** Product details for [PlusProducts.ALL]; empty if Play is unavailable. */
    suspend fun offers(): List<ProductOffer>
    /** Opens the Play purchase sheet. Results arrive through [purchases]. */
    suspend fun launchPurchase(activity: Activity, productId: String): Boolean
}

/** Used when Play Billing cannot be created (e.g. no Play Store). Always free. */
object NoBillingGateway : BillingGateway {
    override val purchases: StateFlow<List<OwnedPurchase>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList())
    override suspend fun refreshPurchases() = false
    override suspend fun acknowledge(purchaseToken: String) = false
    override suspend fun offers(): List<ProductOffer> = emptyList()
    override suspend fun launchPurchase(activity: Activity, productId: String) = false
}
