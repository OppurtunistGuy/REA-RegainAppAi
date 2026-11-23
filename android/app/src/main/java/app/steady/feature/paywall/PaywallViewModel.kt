package app.steady.feature.paywall

import android.app.Activity
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.steady.R
import app.steady.data.Entitlement
import app.steady.data.Tier
import app.steady.remote.EntitlementLogic
import app.steady.remote.PlusProducts
import app.steady.remote.ProductOffer
import app.steady.remote.RemoteModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PaywallUiState(
    val entitlement: Entitlement = EntitlementLogic.FREE,
    /** Formatted Play prices; null → show the default (₹149 / ₹999) copy. */
    val monthlyPrice: String? = null,
    val yearlyPrice: String? = null,
    val busy: Boolean = false,
    @StringRes val message: Int? = null,
) {
    val isPlus: Boolean get() = entitlement.tier == Tier.PLUS
}

/** Picks the Play price for each product; null when Play didn't return it. */
fun pricesFrom(offers: List<ProductOffer>): Pair<String?, String?> =
    offers.firstOrNull { it.productId == PlusProducts.MONTHLY }?.formattedPrice to
        offers.firstOrNull { it.productId == PlusProducts.YEARLY }?.formattedPrice

class PaywallViewModel(private val remote: RemoteModule) : ViewModel() {
    private val _state = MutableStateFlow(PaywallUiState())
    val state: StateFlow<PaywallUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { remote.entitlements.entitlement.collect { e -> _state.update { it.copy(entitlement = e) } } }
        viewModelScope.launch {
            val (m, y) = pricesFrom(runCatching { remote.billing.offers() }.getOrDefault(emptyList()))
            _state.update { it.copy(monthlyPrice = m, yearlyPrice = y) }
        }
    }

    fun subscribe(activity: Activity?, productId: String) {
        if (activity == null || _state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val ok = runCatching { remote.billing.launchPurchase(activity, productId) }.getOrDefault(false)
            _state.update { it.copy(busy = false, message = if (ok) null else R.string.paywall_billing_unavailable) }
        }
    }

    fun restore() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            runCatching { remote.entitlements.refresh() }
            val plus = remote.entitlements.entitlement.value.tier == Tier.PLUS
            _state.update {
                it.copy(busy = false, message = if (plus) R.string.paywall_restored_plus else R.string.paywall_restored_none)
            }
        }
    }
}
