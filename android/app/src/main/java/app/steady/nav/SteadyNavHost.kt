package app.steady.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.steady.data.isConfigured
import app.steady.di.AppContainer
import app.steady.feature.disclosure.DisclosureScreen
import app.steady.feature.focus.FocusScreen
import app.steady.feature.insights.InsightsScreen
import app.steady.feature.intercept.InterceptScreen
import app.steady.feature.learning.ReviewScreen
import app.steady.feature.learning.SwapScreen
import app.steady.feature.partner.PartnerScreen
import app.steady.feature.paywall.PaywallScreen
import app.steady.feature.recap.RecapScreen
import app.steady.feature.settings.SettingsScreen
import app.steady.feature.setup.SetupScreen
import app.steady.feature.today.TodayScreen
import kotlinx.coroutines.flow.first

private class NavControllerNavigator(private val nav: NavHostController) : Navigator {
    override fun go(route: Route) = nav.navigate(route) { launchSingleTop = true }
    override fun back() {
        if (!nav.popBackStack()) nav.navigate(Today) { launchSingleTop = true }
    }
    override fun resetTo(route: Route) = nav.navigate(route) {
        popUpTo(nav.graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

/** Start at Setup if no plan has been saved yet, else Today. */
@Composable
fun SteadyNavHost(container: AppContainer, modifier: Modifier = Modifier) {
    val start: Route? by produceState<Route?>(initialValue = null, container) {
        value = if (container.planRepo.plan.first().isConfigured) Today else Setup
    }
    val startRoute = start
    if (startRoute == null) {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val navController = rememberNavController()
    val navigator = remember(navController) { NavControllerNavigator(navController) }

    NavHost(navController = navController, startDestination = startRoute, modifier = modifier) {
        composable<Setup> { SetupScreen(container, navigator) }
        composable<Today> { TodayScreen(container, navigator) }
        composable<Intercept> { InterceptScreen(container, navigator, it.toRoute<Intercept>()) }
        composable<Swap> { SwapScreen(container, navigator) }
        composable<Focus> { FocusScreen(container, navigator, it.toRoute<Focus>()) }
        composable<Recap> { RecapScreen(container, navigator) }
        composable<Review> { ReviewScreen(container, navigator) }
        composable<Insights> { InsightsScreen(container, navigator) }
        composable<Partner> { PartnerScreen(container, navigator) }
        composable<Paywall> { PaywallScreen(container, navigator) }
        composable<Settings> { SettingsScreen(container, navigator) }
        composable<Disclosure> { DisclosureScreen(container, navigator) }
    }
}
