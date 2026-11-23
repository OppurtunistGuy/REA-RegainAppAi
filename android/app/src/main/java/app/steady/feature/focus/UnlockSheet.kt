package app.steady.feature.focus

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.steady.R
import app.steady.ui.components.GhostButton
import app.steady.ui.components.OptionChip
import app.steady.ui.components.PrimaryButton
import app.steady.ui.components.ScreenTitle
import app.steady.ui.components.SectionLabel
import app.steady.ui.components.SubText
import app.steady.ui.theme.Steady
import app.steady.ui.theme.SteadyTheme
import java.time.Instant

/** Body of the emergency unlock bottom sheet: wait, reason, then ask the key holder or self-grant. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnlockSheetContent(
    state: FocusUiState,
    onReason: (String) -> Unit,
    onSurface: (String?) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenPartner: () -> Unit = {},
) {
    val c = Steady.colors
    val sheet = state.unlock
    val view = state.unlockView
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.screen)
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle(stringResource(R.string.unlock_title))
        val who = state.keyHolderName ?: stringResource(R.string.unlock_your_key_holder)
        when (val phase = sheet.phase) {
            is UnlockPhase.Pending -> {
                Message(stringResource(R.string.unlock_pending, who, state.approvalMinutes))
                PrimaryButton(stringResource(R.string.unlock_back), onDismiss)
            }
            is UnlockPhase.Granted -> {
                val minutes = phase.approvedMinutes
                Message(
                    if (minutes != null) stringResource(R.string.unlock_granted_keyholder, who, minutes, state.grantedUntilLabel ?: "")
                    else stringResource(R.string.unlock_granted, state.grantedUntilLabel ?: ""),
                )
                PrimaryButton(stringResource(R.string.unlock_back), onDismiss)
            }
            UnlockPhase.Denied -> {
                Message(stringResource(R.string.unlock_denied, who))
                PrimaryButton(stringResource(R.string.unlock_back), onDismiss)
            }
            UnlockPhase.NoAnswer -> {
                Message(stringResource(R.string.unlock_no_answer, who))
                PrimaryButton(stringResource(R.string.unlock_back), onDismiss)
            }
            UnlockPhase.NeedsSignIn -> {
                Message(stringResource(R.string.unlock_needs_sign_in, who))
                PrimaryButton(stringResource(R.string.unlock_open_settings), onOpenSettings)
                GhostButton(stringResource(R.string.unlock_back), onDismiss)
            }
            UnlockPhase.NeedsKeyHolder -> {
                Message(stringResource(R.string.unlock_needs_key_holder, who))
                PrimaryButton(stringResource(R.string.unlock_open_partner), onOpenPartner)
                GhostButton(stringResource(R.string.unlock_back), onDismiss)
            }
            UnlockPhase.Closed, UnlockPhase.Form, UnlockPhase.Sending, UnlockPhase.Failed -> {
                SubText(stringResource(R.string.unlock_intro))
                Text(
                    if (view.secondsLeft > 0) pluralStringResource(R.plurals.unlock_wait_seconds, view.secondsLeft, view.secondsLeft)
                    else stringResource(R.string.unlock_wait_done),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (view.secondsLeft > 0) c.urge else c.calm,
                )

                if (state.blocked.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.unlock_what))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OptionChip(stringResource(R.string.unlock_everything), sheet.surfaceId == null, { onSurface(null) }, single = true)
                        state.blocked.forEach { chip ->
                            OptionChip(chip.name, sheet.surfaceId == chip.surfaceId, { onSurface(chip.surfaceId) }, single = true)
                        }
                    }
                }

                val hint = view.reasonHint
                OutlinedTextField(
                    value = sheet.reason,
                    onValueChange = onReason,
                    label = { Text(stringResource(R.string.unlock_reason_label)) },
                    placeholder = { Text(stringResource(R.string.unlock_reason_hint)) },
                    isError = hint == ReasonHint.SHORT,
                    supportingText = {
                        val text = when (hint) {
                            ReasonHint.SHORT -> stringResource(R.string.unlock_reason_short)
                            ReasonHint.OK -> stringResource(R.string.unlock_reason_ok)
                            ReasonHint.OPTIONAL -> stringResource(R.string.unlock_reason_optional)
                            ReasonHint.NONE -> null
                        }
                        if (text != null) Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    enabled = sheet.phase != UnlockPhase.Sending,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (sheet.phase == UnlockPhase.Failed) {
                    Message(stringResource(R.string.unlock_failed))
                }

                val submitLabel = when {
                    sheet.phase == UnlockPhase.Sending -> stringResource(R.string.unlock_sending)
                    view.asksKeyHolder && state.keyHolderName != null -> stringResource(R.string.unlock_ask, state.keyHolderName)
                    else -> stringResource(R.string.unlock_self, state.approvalMinutes)
                }
                PrimaryButton(submitLabel, onSubmit, enabled = view.canSubmit && sheet.phase != UnlockPhase.Sending)
                GhostButton(stringResource(R.string.unlock_cancel), onDismiss)
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyLarge,
        color = Steady.colors.ink,
    )
}

// ---- Previews ----

private val previewOpenedAt: Instant = Instant.parse("2026-11-24T12:14:30Z")

@Preview(name = "Unlock · waiting · light", showBackground = true)
@Composable
private fun UnlockWaitingPreview() {
    val unlock = UnlockSheetState(phase = UnlockPhase.Form, waitStartedAt = previewOpenedAt, reason = "need it")
    SteadyTheme(darkTheme = false) {
        UnlockSheetContent(previewFocusState(now = previewOpenedAt.plusSeconds(12), unlock = unlock), {}, {}, {}, {})
    }
}

@Preview(name = "Unlock · pending · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UnlockPendingPreview() {
    val unlock = UnlockSheetState(phase = UnlockPhase.Pending("r1"), pendingRequestId = "r1")
    SteadyTheme(darkTheme = true) {
        UnlockSheetContent(previewFocusState(unlock = unlock), {}, {}, {}, {})
    }
}
