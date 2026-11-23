package app.steady.enforcement

import android.content.Context
import android.content.Intent
import app.steady.MainActivity
import app.steady.nav.Disclosure
import app.steady.nav.Focus
import app.steady.nav.Route
import app.steady.nav.Swap
import app.steady.nav.Today

/**
 * Deep-link contract between enforcement (overlay, notification) and [MainActivity].
 * MainActivity reads [EXTRA_ROUTE] / [EXTRA_SCHEDULE_ID] in onCreate and onNewIntent and hands them
 * to SteadyNavHost, which maps them with [launchRouteFor] (via `RouteTable.deepLink`) and navigates.
 */
const val EXTRA_ROUTE = "steady.route"

/** Optional schedule id for [ROUTE_FOCUS]. */
const val EXTRA_SCHEDULE_ID = "steady.scheduleId"

const val ROUTE_SWAP = "swap"
const val ROUTE_FOCUS = "focus"
const val ROUTE_TODAY = "today"
const val ROUTE_DISCLOSURE = "disclosure"

/** Maps the [EXTRA_ROUTE] value to a nav route; null for missing / unknown values. */
fun launchRouteFor(route: String?, scheduleId: String? = null): Route? = when (route) {
    ROUTE_SWAP -> Swap
    ROUTE_FOCUS -> Focus(scheduleId)
    ROUTE_TODAY -> Today
    ROUTE_DISCLOSURE -> Disclosure
    else -> null
}

/** Intent that opens the app on [route]. Safe to start from a service (NEW_TASK). */
fun launchIntent(context: Context, route: String, scheduleId: String? = null): Intent =
    Intent(context, MainActivity::class.java)
        .putExtra(EXTRA_ROUTE, route)
        .apply { if (scheduleId != null) putExtra(EXTRA_SCHEDULE_ID, scheduleId) }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
