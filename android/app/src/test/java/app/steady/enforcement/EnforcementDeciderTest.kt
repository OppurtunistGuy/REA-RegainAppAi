package app.steady.enforcement

import app.steady.detect.Detection
import app.steady.detect.Detector
import app.steady.detect.DetectorConfig
import app.steady.detect.EnforcementLevel
import app.steady.detect.Matcher
import app.steady.rules.Plan
import app.steady.rules.RulesEngine
import app.steady.rules.Schedule
import app.steady.rules.Surface
import app.steady.rules.SurfaceMode
import app.steady.rules.UnlockGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class EnforcementDeciderTest {
    private val rules = RulesEngine(ZoneOffset.UTC)
    private val allDays = (1..7).toSet()
    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val game = "com.pubg.imobile"
    private val igSections = listOf("instagram.reels")
    private val ytSections = listOf("youtube.shorts")

    private val plan = Plan(
        distractions = listOf(
            Surface("instagram.reels", SurfaceMode.HIDE_SECTION),
            Surface("youtube.shorts", SurfaceMode.BLOCK_APP),
            Surface("app:$game", SurfaceMode.BLOCK_APP),
        ),
        schedules = listOf(Schedule("s1", "Coaching block", allDays, "09:00", "11:00", strict = true)),
    )

    // 2026-11-23 is a Monday.
    private val inBlock = Instant.parse("2026-11-23T10:00:00Z")
    private val afterBlock = Instant.parse("2026-11-23T12:00:00Z")

    private fun hit(surface: String?) = Detection(surface, 1, null)

    private fun decide(
        d: EnforcementDecider,
        pkg: String = ig,
        detection: Detection? = hit("instagram.reels"),
        sections: List<String> = igSections,
        plan: Plan = this.plan,
        grants: List<UnlockGrant> = emptyList(),
        now: Instant = inBlock,
        healthy: Boolean = true,
    ) = d.decide(pkg, detection, sections, plan, grants, now, healthy)

    @Test fun hideSectionHitGoesBack() {
        val r = decide(EnforcementDecider(rules))
        assertEquals(EnforcementAction.BACK_AND_INTERCEPT, r.action)
        assertEquals("instagram.reels", r.surfaceId)
        assertEquals(EnforcementLevel.HIDE_SECTION, r.level)
        assertEquals("s1", r.scheduleId)
        assertTrue(r.strict)
    }

    @Test fun noDetectionMeansNone() {
        assertEquals(EnforcementDecision.None, decide(EnforcementDecider(rules), detection = hit(null)))
        assertEquals(EnforcementDecision.None, decide(EnforcementDecider(rules), detection = null))
    }

    @Test fun idleStateMeansNone() {
        val d = EnforcementDecider(rules)
        assertEquals(EnforcementDecision.None, decide(d, now = afterBlock))
        assertEquals(EnforcementDecision.None, decide(d, pkg = game, sections = emptyList(), now = afterBlock))
        assertFalse(d.anyBlocked(ig, igSections, plan, emptyList(), afterBlock))
        assertTrue(d.anyBlocked(ig, igSections, plan, emptyList(), inBlock))
    }

    @Test fun surfaceGrantAllowsThatSurfaceOnly() {
        val grant = UnlockGrant("g", "instagram.reels", inBlock.minusSeconds(60), inBlock.plusSeconds(600), null)
        val d = EnforcementDecider(rules)
        assertEquals(EnforcementDecision.None, decide(d, grants = listOf(grant)))
        assertFalse(d.anyBlocked(ig, igSections, plan, listOf(grant), inBlock))
        assertEquals(
            EnforcementAction.HOME_AND_INTERCEPT,
            decide(d, pkg = yt, detection = null, sections = ytSections, grants = listOf(grant)).action,
        )
    }

    @Test fun expiredGrantDoesNotAllow() {
        val grant = UnlockGrant("g", "instagram.reels", inBlock.minusSeconds(600), inBlock.minusSeconds(1), null)
        assertEquals(EnforcementAction.BACK_AND_INTERCEPT, decide(EnforcementDecider(rules), grants = listOf(grant)).action)
    }

    @Test fun globalGrantAllowsEverything() {
        val grant = UnlockGrant("g", null, inBlock.minusSeconds(60), inBlock.plusSeconds(600), "Sam")
        val d = EnforcementDecider(rules)
        assertEquals(EnforcementDecision.None, decide(d, grants = listOf(grant)))
        assertEquals(EnforcementDecision.None, decide(d, pkg = game, sections = emptyList(), grants = listOf(grant)))
        assertEquals(EnforcementDecision.None, decide(d, pkg = yt, sections = ytSections, grants = listOf(grant)))
    }

    @Test fun unhealthyDetectorFallsBackToBlockingTheApp() {
        val r = decide(EnforcementDecider(rules), detection = hit(null), healthy = false)
        assertEquals(EnforcementAction.HOME_AND_INTERCEPT, r.action)
        assertEquals("instagram.reels", r.surfaceId)
        assertEquals(EnforcementLevel.BLOCK_APP, r.level)
    }

    @Test fun blockAppModeGoesHomeWithoutDetection() {
        val r = decide(EnforcementDecider(rules), pkg = yt, detection = null, sections = ytSections)
        assertEquals(EnforcementAction.HOME_AND_INTERCEPT, r.action)
        assertEquals("youtube.shorts", r.surfaceId)
    }

    @Test fun wholeAppSurfaceGoesHome() {
        val r = decide(EnforcementDecider(rules), pkg = game, detection = null, sections = emptyList())
        assertEquals(EnforcementAction.HOME_AND_INTERCEPT, r.action)
        assertEquals("app:$game", r.surfaceId)
        assertEquals(EnforcementLevel.BLOCK_APP, r.level)
    }

    @Test fun appBlockWinsOverSection() {
        val p = plan.copy(distractions = plan.distractions + Surface("app:$ig", SurfaceMode.BLOCK_APP))
        val r = decide(EnforcementDecider(rules), plan = p)
        assertEquals(EnforcementAction.HOME_AND_INTERCEPT, r.action)
        assertEquals("app:$ig", r.surfaceId)
    }

    @Test fun sectionNotInPlanIsIgnored() {
        val r = decide(EnforcementDecider(rules), pkg = "com.snapchat.android", detection = hit("snapchat.spotlight"), sections = listOf("snapchat.spotlight"))
        assertEquals(EnforcementDecision.None, r)
    }

    @Test fun detectionOfOtherSurfaceDoesNotGoBack() {
        val r = decide(EnforcementDecider(rules), detection = hit("youtube.shorts"))
        assertEquals(EnforcementDecision.None, r)
    }

    @Test fun nonStrictBlockStillInterceptsButIsMarkedGentle() {
        val p = plan.copy(schedules = listOf(Schedule("s2", "Soft", allDays, "09:00", "11:00", strict = false)))
        val r = decide(EnforcementDecider(rules), plan = p)
        assertEquals(EnforcementAction.BACK_AND_INTERCEPT, r.action)
        assertFalse(r.strict)
        assertEquals("s2", r.scheduleId)
    }

    @Test fun rateLimitedPerPackage() {
        val d = EnforcementDecider(rules)
        assertEquals(EnforcementAction.BACK_AND_INTERCEPT, decide(d).action)
        assertEquals(EnforcementDecision.None, decide(d, now = inBlock.plusMillis(1499)))
        // Another package is not affected.
        assertEquals(EnforcementAction.HOME_AND_INTERCEPT, decide(d, pkg = game, sections = emptyList(), now = inBlock.plusMillis(10)).action)
        assertEquals(EnforcementAction.BACK_AND_INTERCEPT, decide(d, now = inBlock.plusMillis(1500)).action)
    }

    @Test fun noneDoesNotConsumeRateLimit() {
        val d = EnforcementDecider(rules)
        assertEquals(EnforcementDecision.None, decide(d, detection = hit(null)))
        assertEquals(EnforcementAction.BACK_AND_INTERCEPT, decide(d, now = inBlock.plusMillis(100)).action)
    }

    @Test fun helpers() {
        val cfg = DetectorConfig(
            2,
            listOf(
                Detector("instagram.reels", ig, listOf(Matcher(viewIdEndsWith = ":id/x")), anchor = Matcher(viewIdEndsWith = ":id/tab_bar")),
                Detector("facebook.reels", "com.facebook.katana", listOf(Matcher(descContains = "Reels"))),
            ),
        )
        assertEquals(listOf("instagram.reels"), sectionSurfaces(cfg, ig))
        assertEquals(emptyList<String>(), sectionSurfaces(cfg, game))
        assertTrue(hasAnchor(cfg, ig))
        assertFalse(hasAnchor(cfg, "com.facebook.katana"))
        assertEquals(setOf(ig, "com.facebook.katana", game), observedPackages(cfg, plan))
        assertEquals("app:x", appSurfaceId("x"))
        assertEquals("Coaching block", scheduleLabel(plan, "s1"))
        assertEquals("your focus block", scheduleLabel(plan, "missing"))
        assertEquals("fallback", scheduleLabel(plan, null, "fallback"))
    }

    @Test fun launchRoutes() {
        assertEquals(app.steady.nav.Swap, launchRouteFor(ROUTE_SWAP))
        assertEquals(app.steady.nav.Focus("s1"), launchRouteFor(ROUTE_FOCUS, "s1"))
        assertEquals(app.steady.nav.Today, launchRouteFor(ROUTE_TODAY))
        assertEquals(app.steady.nav.Disclosure, launchRouteFor(ROUTE_DISCLOSURE))
        assertNull(launchRouteFor(null))
        assertNull(launchRouteFor("nope"))
    }
}
