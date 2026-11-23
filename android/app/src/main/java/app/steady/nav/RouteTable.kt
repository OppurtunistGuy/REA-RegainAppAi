package app.steady.nav

import app.steady.data.isConfigured
import app.steady.enforcement.launchRouteFor
import app.steady.rules.Plan

/**
 * Pure facts about the route graph, kept out of the composable so JVM tests can check them:
 * every screen [SteadyNavHost] registers, the start destination and deep-link parsing.
 */
object RouteTable {
    /** One sample instance per destination registered in [SteadyNavHost]. */
    val all: List<Route> = listOf(
        Setup, Today, Intercept(), Swap, Focus(), Recap, Review, Insights, Partner, Paywall, Settings, Disclosure,
    )

    /** Stable screen name per route. Exhaustive `when`: adding a route without a name fails to compile. */
    fun name(route: Route): String = when (route) {
        Setup -> "setup"
        Today -> "today"
        is Intercept -> "intercept"
        Swap -> "swap"
        is Focus -> "focus"
        Recap -> "recap"
        Review -> "review"
        Insights -> "insights"
        Partner -> "partner"
        Paywall -> "paywall"
        Settings -> "settings"
        Disclosure -> "disclosure"
    }

    /** Setup until a plan has been saved, Today afterwards. */
    fun start(plan: Plan): Route = if (plan.isConfigured) Today else Setup

    /**
     * Route for an `EXTRA_ROUTE` deep link (overlay "swap", notification "focus", ...). Before Setup is
     * done only the Accessibility disclosure makes sense; anything else falls back to the start screen.
     */
    fun deepLink(extraRoute: String?, scheduleId: String?, planConfigured: Boolean): Route? {
        val route = launchRouteFor(extraRoute, scheduleId) ?: return null
        return if (planConfigured || route == Disclosure) route else null
    }
}
