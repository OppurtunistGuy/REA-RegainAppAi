package app.steady.feature.insights

import androidx.compose.runtime.Composable
import app.steady.di.AppContainer
import app.steady.nav.Navigator
import app.steady.ui.components.StubScreen
import app.steady.ui.components.SubText

/**
 * TODO(phase8): replace this body. Weekly goal progress (no streaks) and urge patterns.
 * Signature is fixed by the app shell (nav/SteadyNavHost.kt).
 */
@Composable
fun InsightsScreen(container: AppContainer, nav: Navigator) {
    StubScreen(name = "Insights", phase = 8, onBack = nav::back) {
        SubText("Weekly goal progress (no streaks) and urge patterns.")
    }
}
