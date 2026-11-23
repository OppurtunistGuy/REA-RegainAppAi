package app.steady.feature.paywall

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.steady.R
import app.steady.di.AppContainer
import app.steady.feature.settings.Feedback
import app.steady.nav.Navigator
import app.steady.remote.PlusProducts
import app.steady.remote.remote
import app.steady.ui.components.GhostButton
import app.steady.ui.components.LinkButton
import app.steady.ui.components.Pill
import app.steady.ui.components.PillTone
import app.steady.ui.components.PrimaryButton
import app.steady.ui.components.ScreenTitle
import app.steady.ui.components.SectionLabel
import app.steady.ui.components.SteadyCard
import app.steady.ui.components.SubText
import app.steady.ui.components.SteadyScreen
import app.steady.ui.theme.Steady

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Honest plan comparison (prototype "Plans"). No countdowns, no pre-selected upsell, no fake
 * discounts: the same two buttons, the real Play price, and how to cancel.
 */
@Composable
fun PaywallScreen(container: AppContainer, nav: Navigator) {
    val remote = container.remote
    SteadyScreen {
        SectionLabel(stringResource(R.string.paywall_title))
        ScreenTitle(stringResource(R.string.paywall_strict_free))
        SubText(stringResource(R.string.paywall_lede))

        TierCard(
            title = stringResource(R.string.paywall_free),
            price = stringResource(R.string.paywall_free_price),
            items = listOf(R.string.paywall_free_1, R.string.paywall_free_2, R.string.paywall_free_3, R.string.paywall_free_4),
            highlight = false,
        )

        if (remote == null) {
            TierCard(
                title = stringResource(R.string.paywall_plus),
                price = stringResource(R.string.paywall_plus_price, stringResource(R.string.paywall_default_monthly), stringResource(R.string.paywall_default_yearly)),
                items = plusItems,
                highlight = true,
            )
            SubText(stringResource(R.string.acct_unavailable))
            GhostButton(stringResource(R.string.acct_back), nav::back)
            return@SteadyScreen
        }

        val vm: PaywallViewModel = viewModel { PaywallViewModel(remote) }
        val state by vm.state.collectAsStateWithLifecycle()
        val activity = LocalContext.current.findActivity()
        val monthly = state.monthlyPrice ?: stringResource(R.string.paywall_default_monthly)
        val yearly = state.yearlyPrice ?: stringResource(R.string.paywall_default_yearly)

        TierCard(
            title = stringResource(R.string.paywall_plus),
            price = stringResource(R.string.paywall_plus_price, monthly, yearly),
            items = plusItems,
            highlight = true,
        )

        state.message?.let { Feedback(stringResource(it), urge = it == R.string.paywall_billing_unavailable) }

        if (state.isPlus) {
            Pill(stringResource(R.string.paywall_active), tone = PillTone.ACCENT)
        } else {
            PrimaryButton(stringResource(R.string.paywall_monthly, monthly), { vm.subscribe(activity, PlusProducts.MONTHLY) }, enabled = !state.busy)
            GhostButton(stringResource(R.string.paywall_yearly, yearly), { vm.subscribe(activity, PlusProducts.YEARLY) }, enabled = !state.busy)
        }
        LinkButton(stringResource(R.string.paywall_restore), vm::restore)
        SubText(stringResource(R.string.paywall_fine_print))
        GhostButton(stringResource(R.string.acct_back), nav::back)
    }
}

private val plusItems = listOf(
    R.string.paywall_plus_1, R.string.paywall_plus_2, R.string.paywall_plus_3, R.string.paywall_plus_4, R.string.paywall_plus_5,
)

@Composable
private fun TierCard(title: String, price: String, items: List<Int>, highlight: Boolean) {
    val c = Steady.colors
    SteadyCard(
        if (highlight) Modifier.border(BorderStroke(2.dp, c.accent), MaterialTheme.shapes.large) else Modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = c.ink)
        }
        Text(price, style = MaterialTheme.typography.bodyLarge, color = c.ink)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            items.forEach { Text("• " + stringResource(it), style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(start = 2.dp)) }
        }
    }
}
