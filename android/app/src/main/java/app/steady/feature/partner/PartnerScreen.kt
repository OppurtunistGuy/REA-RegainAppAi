package app.steady.feature.partner

import androidx.compose.runtime.Composable
import app.steady.di.AppContainer
import app.steady.nav.Navigator
import app.steady.ui.components.StubScreen
import app.steady.ui.components.SubText

/**
 * TODO(phase11): replace this body. Invite a friend or parent as key holder, see unlock requests.
 * Signature is fixed by the app shell (nav/SteadyNavHost.kt).
 */
@Composable
fun PartnerScreen(container: AppContainer, nav: Navigator) {
    StubScreen(name = "Key holder", phase = 11, onBack = nav::back) {
        SubText("Invite a friend or parent as key holder, see unlock requests.")
    }
}
