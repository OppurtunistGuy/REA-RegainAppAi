package app.steady.enforcement

import app.steady.detect.DetectorConfig
import app.steady.feature.setup.PlanBuilder
import app.steady.feature.setup.SetupSelection
import app.steady.rules.Plan
import app.steady.rules.Relation
import app.steady.rules.RulesEngine
import app.steady.rules.Schedule
import app.steady.rules.Surface
import app.steady.rules.SurfaceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class ProtectedAppsTest {
    private val protected = listOf(
        "com.android.settings", "com.android.vending", "com.google.android.packageinstaller", "com.google.android.dialer",
        "com.android.phone", "com.android.emergency", "com.google.android.cellbroadcastreceiver", "app.steady",
        "com.android.settings.intelligence",
    )
    private val plan = Plan(
        distractions = protected.map { Surface("app:$it", SurfaceMode.BLOCK_APP) } + Surface("app:com.pubg.imobile", SurfaceMode.BLOCK_APP),
        schedules = listOf(Schedule("s1", "All day", (1..7).toSet(), "00:00", "00:00")),
    )
    private val now = Instant.parse("2026-11-23T10:00:00Z")

    @Test fun denylist() {
        protected.forEach { assertTrue(it, ProtectedApps.isProtected(it)) }
        assertFalse(ProtectedApps.isProtected("com.pubg.imobile"))
        assertFalse(ProtectedApps.isProtected("com.instagram.android"))
        assertTrue(ProtectedApps.isProtectedSurface("app:com.android.settings"))
        assertFalse(ProtectedApps.isProtectedSurface("instagram.reels"))
    }

    @Test fun deciderNeverActsOnProtectedApps() {
        val d = EnforcementDecider(RulesEngine(ZoneOffset.UTC))
        protected.forEach { pkg ->
            assertFalse(pkg, d.anyBlocked(pkg, emptyList(), plan, emptyList(), now))
            assertEquals(pkg, EnforcementAction.NONE, d.decide(pkg, null, emptyList(), plan, emptyList(), now, healthy = true).action)
        }
        assertEquals(EnforcementAction.HOME_AND_INTERCEPT, d.decide("com.pubg.imobile", null, emptyList(), plan, emptyList(), now, true).action)
    }

    @Test fun protectedAppsAreNotObserved() {
        assertEquals(setOf("com.pubg.imobile"), observedPackages(DetectorConfig(1, emptyList()), plan))
    }

    @Test fun setupDropsProtectedAppBlocks() {
        val built = PlanBuilder.build(
            SetupSelection(setOf("instagram.reels"), plan.schedules, Relation.SELF, ""),
            previous = plan.copy(version = 2),
            newId = { "x" },
        )
        // BGMI is a Setup option (not selected here); protected apps from the old plan are dropped.
        assertEquals(listOf("instagram.reels"), built.distractions.map { it.id })
    }
}
