package app.steady.feature.settings

import androidx.compose.runtime.Composable
import app.steady.di.AppContainer
import app.steady.nav.Disclosure
import app.steady.nav.Navigator
import app.steady.nav.Partner
import app.steady.nav.Paywall
import app.steady.nav.Setup
import app.steady.ui.components.GhostButton
import app.steady.ui.components.StubScreen
import app.steady.ui.components.SubText

/**
 * TODO(phase11): replace this body. Account, sync, privacy controls, data export/delete.
 * Signature is fixed by the app shell (nav/SteadyNavHost.kt).
 */
@Composable
fun SettingsScreen(container: AppContainer, nav: Navigator) {
    StubScreen(name = "Settings", phase = 11, onBack = nav::back) {
        SubText("Account, sync and privacy controls will live here.")
        GhostButton("Edit my plan", { nav.go(Setup) })
        GhostButton("Key holder", { nav.go(Partner) })
        GhostButton("Steady Plus", { nav.go(Paywall) })
        GhostButton("Accessibility disclosure", { nav.go(Disclosure) })
    }
}
