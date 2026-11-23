package app.steady.feature.disclosure

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.steady.R
import app.steady.di.AppContainer
import app.steady.enforcement.rememberAccessibilityEnabled
import app.steady.nav.Navigator
import app.steady.ui.components.GhostButton
import app.steady.ui.components.Pill
import app.steady.ui.components.PillTone
import app.steady.ui.components.PrimaryButton
import app.steady.ui.components.ScreenTitle
import app.steady.ui.components.SectionLabel
import app.steady.ui.components.SteadyCard
import app.steady.ui.components.SteadyScreen
import app.steady.ui.components.SubText
import app.steady.ui.theme.Steady

/**
 * Google Play "prominent disclosure" shown in-app before the user is sent to Accessibility
 * settings: what is read, what is never collected, on-device only, and an explicit choice.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun DisclosureScreen(container: AppContainer, nav: Navigator) {
    val context = LocalContext.current
    val enabled by rememberAccessibilityEnabled()

    SteadyScreen {
        SectionLabel(stringResource(R.string.disclosure_label))
        ScreenTitle(stringResource(R.string.disclosure_title))
        SubText(stringResource(R.string.disclosure_intro))

        DisclosureCard(R.string.disclosure_reads_heading, R.string.disclosure_reads_body)
        DisclosureCard(R.string.disclosure_never_heading, R.string.disclosure_never_body)
        DisclosureCard(R.string.disclosure_device_heading, R.string.disclosure_device_body)

        if (enabled) {
            Pill(stringResource(R.string.disclosure_on), tone = PillTone.CALM)
            SubText(stringResource(R.string.disclosure_enabled))
            PrimaryButton(stringResource(R.string.disclosure_done), nav::back)
            GhostButton(stringResource(R.string.disclosure_open_settings_again), { openAccessibilitySettings(context) })
        } else {
            SubText(stringResource(R.string.disclosure_steps))
            PrimaryButton(stringResource(R.string.disclosure_turn_on), { openAccessibilitySettings(context) })
            GhostButton(stringResource(R.string.disclosure_not_now), nav::back)
        }
    }
}

@Composable
private fun DisclosureCard(@StringRes headingRes: Int, @StringRes bodyRes: Int) {
    SteadyCard {
        Text(
            stringResource(headingRes),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = Steady.colors.ink,
        )
        SubText(stringResource(bodyRes))
    }
}

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
