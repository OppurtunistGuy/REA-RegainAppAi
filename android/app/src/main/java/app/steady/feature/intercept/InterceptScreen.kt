package app.steady.feature.intercept

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.steady.di.AppContainer
import app.steady.nav.Intercept
import app.steady.nav.Navigator
import app.steady.nav.Swap
import app.steady.nav.Today

/**
 * In-app version of the intercept sheet (debug entry from Today). Close goes back to Today,
 * swap opens the 60-second flashcard, "just messages" goes back.
 */
@Composable
fun InterceptScreen(container: AppContainer, nav: Navigator, route: Intercept) {
    val vm: InterceptViewModel = viewModel(key = "intercept-${route.surfaceId}") {
        InterceptViewModel(route.surfaceId, container.planRepo, container.sessionRepo, container.rules, container.zone, container.clock)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        InterceptContent(
            state = state,
            onClose = { nav.resetTo(Today) },
            onSwap = { nav.go(Swap) },
            onMessagesOnly = nav::back,
        )
    }
}
