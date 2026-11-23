package app.steady.feature.paywall

import androidx.compose.runtime.Composable
import app.steady.di.AppContainer
import app.steady.nav.Navigator
import app.steady.ui.components.StubScreen
import app.steady.ui.components.SubText

/**
 * TODO(phase11): replace this body. Plus features: unlimited schedules, desktop sync, notes to cards, task breakdown.
 * Signature is fixed by the app shell (nav/SteadyNavHost.kt).
 */
@Composable
fun PaywallScreen(container: AppContainer, nav: Navigator) {
    StubScreen(name = "Steady Plus", phase = 11, onBack = nav::back) {
        SubText("Plus features: unlimited schedules, desktop sync, notes to cards, task breakdown.")
    }
}
