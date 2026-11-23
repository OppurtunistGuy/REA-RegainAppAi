package app.steady.feature.partner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.steady.R
import app.steady.di.AppContainer
import app.steady.feature.settings.Feedback
import app.steady.nav.Navigator
import app.steady.nav.Paywall
import app.steady.nav.Settings
import app.steady.remote.KeyHolderRequest
import app.steady.remote.remote
import app.steady.ui.components.GhostButton
import app.steady.ui.components.LinkButton
import app.steady.ui.components.OptionChip
import app.steady.ui.components.PrimaryButton
import app.steady.ui.components.ScreenTitle
import app.steady.ui.components.SectionLabel
import app.steady.ui.components.SteadyCard
import app.steady.ui.components.SteadyScreen
import app.steady.ui.components.SubText
import app.steady.ui.theme.Steady
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val surfaceNames = mapOf(
    "instagram.reels" to "Instagram Reels",
    "youtube.shorts" to "YouTube Shorts",
    "snapchat.spotlight" to "Snapchat Spotlight",
    "facebook.reels" to "Facebook Reels",
)

internal fun surfaceLabel(id: String): String =
    surfaceNames[id] ?: id.removePrefix("app:").removePrefix("site:")

@Composable
fun PartnerScreen(container: AppContainer, nav: Navigator) {
    val remote = container.remote
    if (remote == null) {
        SteadyScreen {
            ScreenTitle(stringResource(R.string.partner_title))
            SubText(stringResource(R.string.acct_unavailable))
            NeverSharedCard()
            GhostButton(stringResource(R.string.acct_back), nav::back)
        }
        return
    }
    val vm: PartnerViewModel = viewModel { PartnerViewModel(remote, container.planRepo, container.zone) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SteadyScreen {
        SectionLabel(stringResource(R.string.partner_title))
        ScreenTitle(stringResource(R.string.partner_intro))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OptionChip(stringResource(R.string.partner_mode_owner), state.mode == PartnerMode.OWNER,
                { vm.setMode(PartnerMode.OWNER) }, Modifier.weight(1f), single = true)
            OptionChip(stringResource(R.string.partner_mode_holder), state.mode == PartnerMode.HOLDER,
                { vm.setMode(PartnerMode.HOLDER) }, Modifier.weight(1f), single = true)
        }

        if (!state.signedIn) {
            SteadyCard {
                SubText(stringResource(R.string.partner_sign_in))
                PrimaryButton(stringResource(R.string.partner_open_settings), { nav.go(Settings) })
            }
        } else {
            state.error?.let { Feedback(stringResource(it), urge = true) }
            if (state.showPlusLink) LinkButton(stringResource(R.string.settings_see_plus), { nav.go(Paywall) })
            state.notice?.let { Feedback(stringResource(it), urge = false) }
            when (state.mode) {
                PartnerMode.OWNER -> OwnerContent(state, vm, context, container.zone)
                PartnerMode.HOLDER -> HolderContent(state, vm, container.zone)
            }
        }

        NeverSharedCard()
        GhostButton(stringResource(R.string.acct_back), nav::back)
    }

    state.confirmRemove?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::cancelRemove,
            title = { Text(stringResource(R.string.partner_remove_confirm_title, pending.name)) },
            text = {
                Text(
                    stringResource(
                        if (pending.mode == PartnerMode.OWNER) R.string.partner_remove_holder_body else R.string.partner_remove_owner_body,
                        pending.name,
                    ),
                )
            },
            confirmButton = { TextButton(onClick = vm::confirmRemove) { Text(stringResource(R.string.partner_remove)) } },
            dismissButton = { TextButton(onClick = vm::cancelRemove) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

/** One linked person with a Remove action. */
@Composable
private fun LinkRow(name: String, createdAt: String, zone: ZoneId, onRemove: () -> Unit, extra: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = Steady.colors.ink)
            runCatching {
                DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(zone).format(Instant.parse(createdAt))
            }.getOrNull()?.let { SubText(stringResource(R.string.partner_since, it)) }
            extra()
        }
        LinkButton(stringResource(R.string.partner_remove), onRemove)
    }
}

private fun displayName(name: String, fallback: String): String = name.trim().ifEmpty { fallback }

@Composable
private fun OwnerContent(state: PartnerUiState, vm: PartnerViewModel, context: Context, zone: ZoneId) {
    SteadyCard {
        Text(
            state.keyHolderName?.let { stringResource(R.string.partner_linked, it) } ?: stringResource(R.string.partner_linked_none),
            style = MaterialTheme.typography.bodyLarge,
            color = Steady.colors.ink,
        )
        val invite = state.invite
        if (invite == null) {
            PrimaryButton(stringResource(R.string.partner_invite), vm::createInvite, enabled = !state.busy)
        } else {
            InviteCode(invite.code, invite.expiresAt, zone, context)
            LinkButton(stringResource(R.string.partner_invite_new), vm::createInvite)
        }
    }

    val holders = state.holders
    if (!holders.isNullOrEmpty()) {
        SectionLabel(stringResource(R.string.partner_holders_title))
        SteadyCard {
            val unnamed = stringResource(R.string.acct_friend_fallback)
            holders.forEach { h ->
                val name = displayName(h.keyHolderName, unnamed)
                LinkRow(name, h.createdAt, zone, onRemove = { vm.askRemove(h.linkId, name) })
            }
        }
    }

    SectionLabel(stringResource(R.string.partner_preview))
    val s = state.summary
    SteadyCard {
        if (s == null || s.blocks == 0) {
            SubText(stringResource(R.string.partner_summary_none))
        } else {
            val who = state.myName ?: stringResource(R.string.partner_you)
            Text(pluralStringResource(R.plurals.partner_summary_blocks, s.blocks, who, s.blocks),
                style = MaterialTheme.typography.titleMedium, color = Steady.colors.ink)
            SubText(stringResource(R.string.partner_summary_sub, s.focusMinutes))
        }
    }
}

@Composable
private fun InviteCode(code: String, expiresAt: String, zone: ZoneId, context: Context) {
    var copied by remember(code) { mutableStateOf(false) }
    SectionLabel(stringResource(R.string.partner_code_label))
    Text(
        code,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = code.toCharArray().joinToString(" ") },
        style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
        color = Steady.colors.ink,
        textAlign = TextAlign.Center,
    )
    val expires = runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(zone).format(Instant.parse(expiresAt))
    }.getOrDefault(expiresAt)
    SubText(stringResource(R.string.partner_code_expires, expires))
    val shareText = stringResource(R.string.partner_share_text, code)
    val shareTitle = stringResource(R.string.partner_share_title)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        GhostButton(
            if (copied) stringResource(R.string.partner_copied) else stringResource(R.string.partner_copy),
            {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Steady invite code", code))
                copied = true
            },
            Modifier.weight(1f),
        )
        GhostButton(
            stringResource(R.string.partner_share),
            {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                context.startActivity(Intent.createChooser(send, shareTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun HolderContent(state: PartnerUiState, vm: PartnerViewModel, zone: ZoneId) {
    SteadyCard {
        OutlinedTextField(
            value = state.codeInput,
            onValueChange = vm::setCode,
            label = { Text(stringResource(R.string.partner_enter_code)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(stringResource(R.string.partner_accept), vm::accept, enabled = state.canAccept)
        state.acceptedName?.let { Feedback(stringResource(R.string.partner_accepted, it), urge = false) }
        val owners = state.owners
        if (owners == null && state.heldKeys.isNotEmpty()) {
            // Not loaded from the server yet: the names remembered on this phone.
            SectionLabel(stringResource(R.string.partner_holding))
            state.heldKeys.forEach { Text(it.ownerName, style = MaterialTheme.typography.bodyLarge, color = Steady.colors.ink) }
        }
    }

    val owners = state.owners
    if (!owners.isNullOrEmpty()) {
        SectionLabel(stringResource(R.string.partner_owners_title))
        owners.forEach { o ->
            SteadyCard {
                val name = displayName(o.ownerName, stringResource(R.string.acct_friend_fallback))
                LinkRow(name, o.createdAt, zone, onRemove = { vm.askRemove(o.linkId, name) }) {
                    val s = state.ownerSummaries[o.ownerUserId]
                    SectionLabel(stringResource(R.string.partner_owner_week, name))
                    if (s == null || s.blocks == 0) {
                        SubText(stringResource(R.string.partner_owner_summary_none))
                    } else {
                        Text(pluralStringResource(R.plurals.partner_summary_blocks, s.blocks, name, s.blocks),
                            style = MaterialTheme.typography.titleMedium, color = Steady.colors.ink)
                        SubText(stringResource(R.string.partner_summary_sub, s.focusMinutes))
                    }
                }
            }
        }
    }

    SectionLabel(stringResource(R.string.partner_requests))
    if (state.requests.isEmpty()) {
        SubText(stringResource(R.string.partner_requests_none))
    } else {
        state.requests.forEach { RequestCard(it, deciding = it.id in state.deciding, zone = zone, onDecide = vm::decide) }
    }
    GhostButton(stringResource(R.string.partner_refresh), vm::refresh, enabled = !state.busy)
}

@Composable
private fun RequestCard(r: KeyHolderRequest, deciding: Boolean, zone: ZoneId, onDecide: (String, Boolean) -> Unit) {
    SteadyCard {
        Text(stringResource(R.string.partner_request_title, r.ownerName), style = MaterialTheme.typography.titleMedium, color = Steady.colors.ink)
        SubText(stringResource(R.string.partner_request_reason, r.reason))
        SubText(r.surfaceId?.let { stringResource(R.string.partner_request_surface, surfaceLabel(it)) }
            ?: stringResource(R.string.partner_request_everything))
        runCatching {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zone).format(Instant.parse(r.createdAt))
        }.getOrNull()?.let { SubText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PrimaryButton(stringResource(R.string.partner_approve), { onDecide(r.id, true) }, Modifier.weight(1f), enabled = !deciding)
            GhostButton(stringResource(R.string.partner_deny), { onDecide(r.id, false) }, Modifier.weight(1f), enabled = !deciding)
        }
    }
}

@Composable
private fun NeverSharedCard() {
    SteadyCard {
        SectionLabel(stringResource(R.string.partner_never))
        SubText(stringResource(R.string.partner_never_body))
    }
}
