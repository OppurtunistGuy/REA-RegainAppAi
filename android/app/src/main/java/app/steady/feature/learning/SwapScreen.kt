package app.steady.feature.learning

import androidx.compose.runtime.Composable
import app.steady.di.AppContainer
import app.steady.nav.Navigator
import app.steady.ui.components.StubScreen
import app.steady.ui.components.SubText

/**
 * TODO(phase8): replace this body. Shows one due flashcard (SwapPicker) for 60 seconds, then back to focus.
 * Signature is fixed by the app shell (nav/SteadyNavHost.kt).
 */
@Composable
fun SwapScreen(container: AppContainer, nav: Navigator) {
    StubScreen(name = "60-second swap", phase = 8, onBack = nav::back) {
        SubText("Shows one due flashcard (SwapPicker) for 60 seconds, then back to focus.")
    }
}
