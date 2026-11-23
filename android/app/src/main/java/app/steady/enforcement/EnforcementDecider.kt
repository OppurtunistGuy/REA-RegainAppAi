package app.steady.enforcement

import app.steady.detect.Detection
import app.steady.detect.DetectorConfig
import app.steady.detect.EnforcementLevel
import app.steady.detect.FallbackLadder
import app.steady.rules.BlockState
import app.steady.rules.Plan
import app.steady.rules.RulesEngine
import app.steady.rules.SurfaceMode
import app.steady.rules.UnlockGrant
import java.time.Duration
import java.time.Instant

enum class EnforcementAction { NONE, BACK_AND_INTERCEPT, HOME_AND_INTERCEPT }

data class EnforcementDecision(
    val action: EnforcementAction,
    /** Surface the intercept is recorded under: a section id ("instagram.reels") or "app:<pkg>". */
    val surfaceId: String? = null,
    val level: EnforcementLevel? = null,
    val scheduleId: String? = null,
    /** False in a non-strict block: the overlay must not gate its exits. */
    val strict: Boolean = true,
) {
    companion object {
        val None = EnforcementDecision(EnforcementAction.NONE)
    }
}

private const val APP_PREFIX = "app:"

/** "app:<packageName>" surface id for whole-app blocks. */
fun appSurfaceId(packageName: String): String = "$APP_PREFIX$packageName"

/** Section surfaces the detector config knows for [packageName], in config order. */
fun sectionSurfaces(config: DetectorConfig, packageName: String): List<String> =
    config.detectors.filter { it.packageName == packageName }.map { it.surfaceId }.distinct()

/** True if some detector for [packageName] has an anchor (only then do health samples mean anything). */
fun hasAnchor(config: DetectorConfig, packageName: String): Boolean =
    config.detectors.any { it.packageName == packageName && it.anchor != null }

/** Packages the accessibility service needs to observe: detector packages plus `app:<pkg>` blocks. */
fun observedPackages(config: DetectorConfig, plan: Plan): Set<String> {
    val out = config.detectors.mapTo(LinkedHashSet()) { it.packageName }
    plan.distractions.map { it.id }
        .filter { it.startsWith(APP_PREFIX) }
        .map { it.removePrefix(APP_PREFIX) }
        .filterTo(out) { it.isNotBlank() }
    return out
}

/** Label of the schedule behind [scheduleId], for the overlay copy. */
fun scheduleLabel(plan: Plan, scheduleId: String?, fallback: String = "your focus block"): String =
    plan.schedules.firstOrNull { it.id == scheduleId }?.label?.takeIf { it.isNotBlank() } ?: fallback

/**
 * Pure enforcement decision (no Android types). The only state is the per-package rate limit:
 * at most one BACK/HOME per [cooldown] per package, so a slow app transition cannot loop.
 *
 * Order: an `app:<pkg>` block wins (HOME). Otherwise each blocked section of the package goes
 * through [FallbackLadder]: BLOCK_APP (chosen mode, or unhealthy detector) → HOME; HIDE_SECTION →
 * BACK only when the detector currently sees that section.
 */
class EnforcementDecider(
    private val rules: RulesEngine,
    private val cooldown: Duration = DEFAULT_COOLDOWN,
) {
    private val lastAction = HashMap<String, Instant>()

    /**
     * Cheap pre-check before the tree is read: is anything about [packageName] blocked right now?
     * When false the service does not look at the screen at all.
     */
    fun anyBlocked(packageName: String, sections: List<String>, plan: Plan, grants: List<UnlockGrant>, now: Instant): Boolean =
        (listOf(appSurfaceId(packageName)) + sections).any { rules.isBlocked(plan, grants, it, now) }

    /**
     * @param detection SurfaceDetector result for the current tree, or null if the tree was not read.
     * @param sections section surface ids the detector config has for [packageName].
     * @param healthy DetectorHealth for [packageName].
     */
    @Synchronized
    fun decide(
        packageName: String,
        detection: Detection?,
        sections: List<String>,
        plan: Plan,
        grants: List<UnlockGrant>,
        now: Instant,
        healthy: Boolean,
    ): EnforcementDecision {
        val active = rules.state(plan, now) as? BlockState.Active ?: return EnforcementDecision.None
        val candidate = candidate(packageName, detection, sections, plan, grants, now, healthy)
            ?: return EnforcementDecision.None
        val last = lastAction[packageName]
        if (last != null && now.isBefore(last.plus(cooldown))) return EnforcementDecision.None
        lastAction[packageName] = now
        return candidate.copy(scheduleId = active.scheduleId, strict = active.strict)
    }

    private fun candidate(
        packageName: String,
        detection: Detection?,
        sections: List<String>,
        plan: Plan,
        grants: List<UnlockGrant>,
        now: Instant,
        healthy: Boolean,
    ): EnforcementDecision? {
        val appId = appSurfaceId(packageName)
        if (rules.isBlocked(plan, grants, appId, now)) {
            return EnforcementDecision(EnforcementAction.HOME_AND_INTERCEPT, appId, EnforcementLevel.BLOCK_APP)
        }
        var back: EnforcementDecision? = null
        for (surfaceId in sections) {
            if (!rules.isBlocked(plan, grants, surfaceId, now)) continue
            val mode = plan.distractions.firstOrNull { it.id == surfaceId }?.mode ?: SurfaceMode.HIDE_SECTION
            when (val level = FallbackLadder.level(mode, healthy, accessibilityOn = true)) {
                // Whole app (by choice or because the detector is unhealthy) wins over hiding a section.
                EnforcementLevel.BLOCK_APP ->
                    return EnforcementDecision(EnforcementAction.HOME_AND_INTERCEPT, surfaceId, level)
                EnforcementLevel.HIDE_SECTION ->
                    if (back == null && detection?.surfaceId == surfaceId) {
                        back = EnforcementDecision(EnforcementAction.BACK_AND_INTERCEPT, surfaceId, level)
                    }
                EnforcementLevel.BLOCK_DOMAIN -> Unit // not reachable while accessibility is on
            }
        }
        return back
    }

    companion object {
        val DEFAULT_COOLDOWN: Duration = Duration.ofMillis(1500)
    }
}
