package app.steady.nav

import app.steady.data.EmptyPlan
import app.steady.enforcement.ROUTE_DISCLOSURE
import app.steady.enforcement.ROUTE_FOCUS
import app.steady.enforcement.ROUTE_SWAP
import app.steady.enforcement.ROUTE_TODAY
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM stand-in for a Compose smoke test of SteadyNavHost: the route table and deep-link parsing. */
class RouteTableTest {
    @Test fun everyRouteHasAUniqueScreen() {
        val names = RouteTable.all.map(RouteTable::name)
        assertEquals(12, names.size)
        assertEquals(names.size, names.toSet().size)
    }

    @Test fun routesRoundTripThroughTheirSerializer() {
        // navigation-compose type-safe routes are encoded with the route's serializer.
        (RouteTable.all + Focus("s1") + Intercept("youtube.shorts", recordOpen = true)).forEach { r ->
            val text = Json.encodeToString(Route.serializer(), r)
            assertEquals(r, Json.decodeFromString(Route.serializer(), text))
        }
    }

    @Test fun startDestination() {
        assertEquals(Setup, RouteTable.start(EmptyPlan))
        assertEquals(Today, RouteTable.start(EmptyPlan.copy(version = 1)))
    }

    @Test fun deepLinks() {
        assertEquals(Swap, RouteTable.deepLink(ROUTE_SWAP, null, planConfigured = true))
        assertEquals(Focus("coaching"), RouteTable.deepLink(ROUTE_FOCUS, "coaching", planConfigured = true))
        assertEquals(Focus(null), RouteTable.deepLink(ROUTE_FOCUS, null, planConfigured = true))
        assertEquals(Today, RouteTable.deepLink(ROUTE_TODAY, null, planConfigured = true))
        assertEquals(Disclosure, RouteTable.deepLink(ROUTE_DISCLOSURE, null, planConfigured = true))
        assertNull(RouteTable.deepLink("unknown", null, planConfigured = true))
        assertNull(RouteTable.deepLink(null, null, planConfigured = true))
    }

    @Test fun beforeSetupOnlyDisclosureDeepLinks() {
        assertNull(RouteTable.deepLink(ROUTE_SWAP, null, planConfigured = false))
        assertNull(RouteTable.deepLink(ROUTE_FOCUS, "x", planConfigured = false))
        assertEquals(Disclosure, RouteTable.deepLink(ROUTE_DISCLOSURE, null, planConfigured = false))
    }

    @Test fun inAppInterceptDoesNotRecordByDefault() {
        assertEquals(false, Intercept().recordOpen)
    }
}
