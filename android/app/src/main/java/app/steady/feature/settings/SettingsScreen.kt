package app.steady.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.steady.Links
import app.steady.R
import app.steady.data.Tier
import app.steady.di.AppContainer
import app.steady.nav.Disclosure
import app.steady.nav.Navigator
import app.steady.nav.Partner
import app.steady.nav.Paywall
import app.steady.nav.Setup
import app.steady.remote.AccountStore
import app.steady.remote.OwnedPurchase
import app.steady.remote.PlusProducts
import app.steady.remote.SignedOutReason
import app.steady.remote.SyncStatus
import app.steady.remote.remote
import app.steady.ui.components.GhostButton
import app.steady.ui.components.LinkButton
import app.steady.ui.components.Pill
import app.steady.ui.components.PillTone
import app.steady.ui.components.PrimaryButton
import app.steady.ui.components.ScreenTitle
import app.steady.ui.components.SectionLabel
import app.steady.ui.components.SteadyCard
import app.steady.ui.components.SteadyScreen
import app.steady.ui.components.SubText
import app.steady.ui.theme.Steady
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Play's subscription centre; with [sku] it opens that subscription directly (one-tap cancel). */
fun manageSubscriptionUrl(sku: String?, packageName: String = "app.steady"): String =
    if (sku == null) "https://play.google.com/store/account/subscriptions"
    else "https://play.google.com/store/account/subscriptions?sku=$sku&package=$packageName"

internal fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser or Play Store: nothing sensible to do.
    }
}

@Composable
fun SettingsScreen(container: AppContainer, nav: Navigator) {
    val remote = container.remote
    if (remote == null) {
        SteadyScreen {
            ScreenTitle(stringResource(R.string.settings_title))
            SubText(stringResource(R.string.acct_unavailable))
            GhostButton(stringResource(R.string.settings_edit_plan), { nav.go(Setup) })
            GhostButton(stringResource(R.string.acct_back), nav::back)
        }
        return
    }
    val vm: SettingsViewModel = viewModel { SettingsViewModel(remote) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SteadyScreen {
        ScreenTitle(stringResource(R.string.settings_title))
        GhostButton(stringResource(R.string.settings_edit_plan), { nav.go(Setup) })

        state.error?.let { Feedback(stringResource(it), urge = true) }
        state.notice?.let { Feedback(stringResource(it), urge = false) }
        state.signedOutReason?.let { reason ->
            SteadyCard {
                Feedback(
                    stringResource(
                        if (reason == SignedOutReason.EXPIRED) R.string.settings_signed_out_expired
                        else R.string.settings_signed_out_rejected,
                    ),
                    urge = true,
                )
                LinkButton(stringResource(R.string.disclosure_done), vm::dismissSignedOut)
            }
        }

        AccountSection(state, vm)
        SyncSection(state.sync, signedIn = state.account != null, onSyncNow = vm::syncNow, zone = container.zone)
        PlanSection(
            tier = state.entitlement.tier,
            purchase = state.activePurchase,
            onSeePlus = { nav.go(Paywall) },
            onManage = { context.openUrl(manageSubscriptionUrl(state.activePurchase?.productIds?.firstOrNull { it in PlusProducts.ALL })) },
        )

        SectionLabel(stringResource(R.string.settings_key_holder))
        GhostButton(stringResource(R.string.settings_key_holder), { nav.go(Partner) })

        PrivacySection(signedIn = state.account != null, onDelete = { vm.askDelete(true) })

        SectionLabel(stringResource(R.string.settings_accessibility))
        SubText(stringResource(R.string.settings_accessibility_body))
        GhostButton(stringResource(R.string.settings_accessibility_open), { nav.go(Disclosure) })

        ServerSection(state, vm)
        GhostButton(stringResource(R.string.acct_back), nav::back)
    }

    if (state.confirmDelete) {
        AlertDialog(
            onDismissRequest = { vm.askDelete(false) },
            title = { Text(stringResource(R.string.settings_delete)) },
            text = { Text(stringResource(R.string.settings_delete_body)) },
            confirmButton = {
                TextButton(onClick = vm::deleteSyncedData, enabled = !state.busy) {
                    Text(stringResource(if (state.busy) R.string.settings_deleting else R.string.settings_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.askDelete(false) }, enabled = !state.busy) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
}

@Composable
internal fun Feedback(text: String, urge: Boolean) {
    Text(
        text,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = if (urge) Steady.colors.urge else Steady.colors.calm,
    )
}

@Composable
private fun AccountSection(state: SettingsUiState, vm: SettingsViewModel) {
    SectionLabel(stringResource(R.string.settings_account))
    SteadyCard {
        val account = state.account
        if (account != null) {
            Text(
                stringResource(R.string.settings_signed_in_as, account.name ?: account.contact ?: account.userId.take(8)),
                style = MaterialTheme.typography.bodyLarge,
                color = Steady.colors.ink,
            )
            account.contact?.let { SubText(it) }
            GhostButton(stringResource(R.string.settings_sign_out), vm::signOut)
            return@SteadyCard
        }
        SubText(stringResource(R.string.settings_account_why))
        when (state.step) {
            OtpStep.CONTACT -> {
                OutlinedTextField(
                    value = state.contact,
                    onValueChange = vm::setContact,
                    label = { Text(stringResource(R.string.settings_contact)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.name,
                    onValueChange = vm::setName,
                    label = { Text(stringResource(R.string.settings_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton(
                    if (state.busy) stringResource(R.string.acct_busy) else stringResource(R.string.settings_send_code),
                    vm::sendCode,
                    enabled = state.canSendCode,
                )
            }
            OtpStep.CODE -> {
                SubText(stringResource(R.string.settings_code_sent, state.contact.trim()))
                OutlinedTextField(
                    value = state.code,
                    onValueChange = vm::setCode,
                    label = { Text(stringResource(R.string.settings_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton(
                    if (state.busy) stringResource(R.string.acct_busy) else stringResource(R.string.settings_verify),
                    vm::verify,
                    enabled = state.canVerify,
                )
                LinkButton(stringResource(R.string.settings_change_contact), vm::changeContact)
            }
        }
    }
}

@Composable
private fun SyncSection(status: SyncStatus, signedIn: Boolean, onSyncNow: () -> Unit, zone: ZoneId) {
    SectionLabel(stringResource(R.string.settings_sync))
    SteadyCard {
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zone)
        val text = when (status) {
            SyncStatus.SignedOut -> stringResource(R.string.settings_sync_signed_out)
            SyncStatus.Idle -> stringResource(R.string.settings_sync_idle)
            SyncStatus.Syncing -> stringResource(R.string.settings_sync_running)
            is SyncStatus.Synced -> status.version?.let { stringResource(R.string.settings_sync_done, time.format(status.at), it) }
                ?: stringResource(R.string.settings_sync_done_empty, time.format(status.at))
            SyncStatus.Offline -> stringResource(R.string.settings_sync_offline)
            SyncStatus.SignInAgain -> stringResource(R.string.settings_sync_sign_in_again)
            is SyncStatus.Failed -> stringResource(R.string.settings_sync_failed)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Steady.colors.ink,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (signedIn) GhostButton(stringResource(R.string.settings_sync_now), onSyncNow, enabled = status != SyncStatus.Syncing)
    }
}

@Composable
private fun PlanSection(tier: Tier, purchase: OwnedPurchase?, onSeePlus: () -> Unit, onManage: () -> Unit) {
    SectionLabel(stringResource(R.string.settings_plan))
    SteadyCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(
                if (tier == Tier.PLUS) stringResource(R.string.paywall_plus) else stringResource(R.string.paywall_free),
                tone = if (tier == Tier.PLUS) PillTone.ACCENT else PillTone.CALM,
            )
        }
        if (tier == Tier.PLUS) {
            SubText(stringResource(R.string.settings_plan_plus))
        } else {
            SubText(stringResource(R.string.settings_plan_free))
            GhostButton(stringResource(R.string.settings_see_plus), onSeePlus)
        }
        if (purchase != null || tier == Tier.PLUS) {
            GhostButton(stringResource(R.string.settings_manage_sub), onManage)
        }
        SubText(stringResource(R.string.settings_renewal))
    }
}

@Composable
private fun PrivacySection(signedIn: Boolean, onDelete: () -> Unit) {
    SectionLabel(stringResource(R.string.settings_privacy))
    SteadyCard {
        PrivacyRow(stringResource(R.string.settings_privacy_device), stringResource(R.string.settings_privacy_device_body))
        PrivacyRow(stringResource(R.string.settings_privacy_synced), stringResource(R.string.settings_privacy_synced_body))
        PrivacyRow(stringResource(R.string.settings_privacy_never), stringResource(R.string.settings_privacy_never_body))
        if (signedIn) GhostButton(stringResource(R.string.settings_delete), onDelete)
        val context = LocalContext.current
        LinkButton(stringResource(R.string.privacy_policy_link), { context.openUrl(Links.PRIVACY_POLICY) })
    }
}

@Composable
private fun PrivacyRow(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = Steady.colors.ink)
    SubText(body)
}

@Composable
private fun ServerSection(state: SettingsUiState, vm: SettingsViewModel) {
    SectionLabel(stringResource(R.string.settings_server))
    SteadyCard {
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = vm::setBaseUrl,
            label = { Text(stringResource(R.string.settings_server_url)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        SubText(stringResource(R.string.settings_server_hint, AccountStore.DEFAULT_BASE_URL))
        GhostButton(stringResource(R.string.settings_server_save), vm::saveBaseUrl, enabled = state.baseUrl.trim() != state.savedBaseUrl)
    }
}
