package app.steady.feature.recap

import androidx.compose.runtime.Composable
import app.steady.di.AppContainer
import app.steady.nav.Navigator
import app.steady.ui.components.StubScreen
import app.steady.ui.components.SubText

/**
 * TODO(phase8): replace this body. After a block: what you got done, intercepts caught, weekly goal.
 * Signature is fixed by the app shell (nav/SteadyNavHost.kt).
 */
@Composable
fun RecapScreen(container: AppContainer, nav: Navigator) {
    StubScreen(name = "Block recap", phase = 8, onBack = nav::back) {
        SubText("After a block: what you got done, intercepts caught, weekly goal.")
    }
}
