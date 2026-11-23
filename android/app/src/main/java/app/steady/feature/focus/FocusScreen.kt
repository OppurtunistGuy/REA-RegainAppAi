package app.steady.feature.focus

import androidx.compose.runtime.Composable
import app.steady.di.AppContainer
import app.steady.nav.Focus
import app.steady.nav.Navigator
import app.steady.nav.Recap
import app.steady.ui.components.PrimaryButton
import app.steady.ui.components.StubScreen
import app.steady.ui.components.SubText

/**
 * TODO(phase7): replace this body. Focus ring countdown (EnergyPlanner minutes), current task,
 * blocked surfaces, emergency unlock (UnlockFlow). Signature is fixed by nav/SteadyNavHost.kt.
 */
@Composable
fun FocusScreen(container: AppContainer, nav: Navigator, route: Focus) {
    StubScreen(name = "Focus block", phase = 7, onBack = nav::back) {
        SubText(if (route.scheduleId != null) "Schedule: ${route.scheduleId}" else "Started from Today")
        PrimaryButton("Finish block", { nav.go(Recap) })
    }
}
