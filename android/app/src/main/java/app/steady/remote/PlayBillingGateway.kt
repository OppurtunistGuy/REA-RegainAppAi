package app.steady.remote

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Google Play Billing (billing-ktx). The library's manifest adds `com.android.vending.BILLING`.
 * Purchases are acknowledged by [RemoteEntitlementRepository] (Play refunds unacknowledged ones
 * after 3 days); RemoteEntitlementRepository sends them to POST /v1/billing/verify when signed in.
 */
class PlayBillingGateway(context: Context) : BillingGateway {
    private val _purchases = MutableStateFlow<List<OwnedPurchase>>(emptyList())
    override val purchases: StateFlow<List<OwnedPurchase>> = _purchases.asStateFlow()

    private val details = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    private val connectMutex = Mutex()

    private val listener = PurchasesUpdatedListener { result, list ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && list != null) {
            val updated = list.map { it.toOwned() }
            _purchases.update { old ->
                (old.filterNot { o -> updated.any { it.purchaseToken == o.purchaseToken } } + updated)
            }
        }
    }

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(listener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private suspend fun connect(): Boolean = connectMutex.withLock {
        if (client.isReady) return@withLock true
        suspendCancellableCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                }
                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) cont.resume(false)
                }
            })
        }
    }

    override suspend fun refreshPurchases(): Boolean {
        if (!connect()) return false
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        val result = client.queryPurchasesAsync(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return false
        _purchases.value = result.purchasesList.map { it.toOwned() }
        return true
    }

    override suspend fun acknowledge(purchaseToken: String): Boolean {
        if (!connect()) return false
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
        val ok = client.acknowledgePurchase(params).responseCode == BillingClient.BillingResponseCode.OK
        if (ok) {
            _purchases.update { l -> l.map { if (it.purchaseToken == purchaseToken) it.copy(acknowledged = true) else it } }
        }
        return ok
    }

    override suspend fun offers(): List<ProductOffer> {
        if (!connect()) return emptyList()
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            PlusProducts.ALL.map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
            },
        ).build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) return emptyList()
        val list = result.productDetailsList.orEmpty()
        details.value = list.associateBy { it.productId }
        return list.mapNotNull { pd ->
            val offer = pd.subscriptionOfferDetails?.firstOrNull() ?: return@mapNotNull null
            // The last pricing phase is the recurring base price (earlier ones are trials/intro).
            val price = offer.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice ?: return@mapNotNull null
            ProductOffer(pd.productId, price, offer.offerToken)
        }
    }

    override suspend fun launchPurchase(activity: Activity, productId: String): Boolean {
        if (!connect()) return false
        if (details.value[productId] == null) offers()
        val pd = details.value[productId] ?: return false
        val offerToken = pd.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return false
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(pd).setOfferToken(offerToken).build(),
            ),
        ).build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    private fun Purchase.toOwned() = OwnedPurchase(
        productIds = products,
        purchaseToken = purchaseToken,
        purchased = purchaseState == Purchase.PurchaseState.PURCHASED,
        acknowledged = isAcknowledged,
        autoRenewing = isAutoRenewing,
    )
}
