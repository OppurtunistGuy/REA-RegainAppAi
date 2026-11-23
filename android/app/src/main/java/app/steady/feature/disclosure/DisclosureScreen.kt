package app.steady.feature.disclosure

import androidx.compose.runtime.Composable
import app.steady.di.AppContainer
import app.steady.nav.Navigator
import app.steady.ui.components.StubScreen
import app.steady.ui.components.SubText

/**
 * TODO(phase6): replace this body. Prominent disclosure before enabling the Accessibility service (Play policy).
 * Signature is fixed by the app shell (nav/SteadyNavHost.kt).
 */
@Composable
fun DisclosureScreen(container: AppContainer, nav: Navigator) {
    StubScreen(name = "How Steady uses Accessibility", phase = 6, onBack = nav::back) {
        SubText("Prominent disclosure before enabling the Accessibility service (Play policy).")
    }
}
