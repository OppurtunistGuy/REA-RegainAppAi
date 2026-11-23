package app.steady.feature.intercept

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.steady.di.AppContainer
import app.steady.nav.Intercept
import app.steady.nav.Navigator
import app.steady.nav.Swap
import app.steady.nav.Today
import app.steady.ui.components.SectionLabel

/**
 * TODO(phase7): replace this body (ViewModel that records the intercept via SessionRepository,
 * reads opens today/baseline and the active schedule label). Signature fixed by nav/SteadyNavHost.kt.
 */
@Composable
fun InterceptScreen(container: AppContainer, nav: Navigator, route: Intercept) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
        verticalArrangement = Arrangement.Bottom,
    ) {
        SectionLabel("Phase 7 · preview")
        InterceptContent(
            state = InterceptUiState(route.surfaceId, scheduleLabel = "your block", opensToday = 0, opensBaseline = 0),
            onClose = { nav.resetTo(Today) },
            onSwap = { nav.go(Swap) },
            onMessagesOnly = { nav.resetTo(Today) },
        )
    }
}
