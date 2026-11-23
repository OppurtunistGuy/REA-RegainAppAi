package app.steady.feature.learning

import androidx.compose.runtime.Composable
import app.steady.di.AppContainer
import app.steady.nav.Navigator
import app.steady.ui.components.StubScreen
import app.steady.ui.components.SubText

/**
 * TODO(phase8): replace this body. Spaced review of due cards and Recall-to-cards entry.
 * Signature is fixed by the app shell (nav/SteadyNavHost.kt).
 */
@Composable
fun ReviewScreen(container: AppContainer, nav: Navigator) {
    StubScreen(name = "Review cards", phase = 8, onBack = nav::back) {
        SubText("Spaced review of due cards and Recall-to-cards entry.")
    }
}
