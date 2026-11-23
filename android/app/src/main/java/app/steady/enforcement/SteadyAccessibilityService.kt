package app.steady.enforcement

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.steady.R
import app.steady.appContainer
import app.steady.data.EmptyPlan
import app.steady.detect.DetectorConfig
import app.steady.detect.DetectorHealth
import app.steady.detect.SurfaceDetector
import app.steady.di.AppContainer
import app.steady.feature.intercept.InterceptStateBuilder
import app.steady.feature.intercept.InterceptUiState
import app.steady.rules.Plan
import app.steady.rules.RulesEngine
import app.steady.rules.UnlockGrant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Hides short-video sections (and whole blocked apps) during scheduled blocks.
 *
 * Privacy: before touching the screen it checks, from the rules alone, whether anything about the
 * foreground package is blocked right now. Only then is the accessibility tree mapped (lazily, in
 * memory) and matched against the detector config. Only the surface id and time of an intercept
 * are recorded; screen contents are never stored or sent.
 */
class SteadyAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var container: AppContainer? = null
    private var overlay: InterceptOverlay? = null
    private var decider: EnforcementDecider? = null
    private var deciderRules: RulesEngine? = null
    private val health = DetectorHealth()

    // Latest values, kept in memory by collectors tied to [scope].
    @Volatile private var plan: Plan = EmptyPlan
    @Volatile private var grants: List<UnlockGrant> = emptyList()

    private var config: DetectorConfig? = null
    private var detector: SurfaceDetector? = null
    private var observed: Set<String> = emptySet()
    private var pending: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val c = appContainer
        container = c
        overlay = InterceptOverlay(this)
        scope.launch {
            c.planRepo.plan.collect {
                plan = it
                refreshObservedPackages()
                runCatching { BlockScheduler.sync(this@SteadyAccessibilityService, c) }
                    .onFailure { e -> Log.w(TAG, "Block sync failed", e) }
            }
        }
        scope.launch { c.unlockRepo.grants.collect { grants = it } }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> Unit
            else -> return
        }
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        // Debounce: the latest event wins; the tree is read once things settle.
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            evaluate()
        }
    }

    /** Rebuilt when `container.rules` changes (a new RulesEngine after a time-zone change). */
    private fun currentDecider(c: AppContainer): EnforcementDecider {
        val rules = c.rules
        decider?.takeIf { deciderRules === rules }?.let { return it }
        deciderRules = rules
        return EnforcementDecider(rules).also { decider = it }
    }

    private suspend fun evaluate() {
        val c = container ?: return
        val d = currentDecider(c)
        val ov = overlay ?: return
        if (ov.isShowing) return
        val cfg = currentConfig(c)
        val det = detector ?: return
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString()
        if (pkg == null || pkg == packageName || pkg !in observed) {
            recycle(root)
            return
        }
        val now = c.clock.instant()
        val plan = plan
        val grants = grants
        val sections = sectionSurfaces(cfg, pkg)
        if (!d.anyBlocked(pkg, sections, plan, grants, now)) {
            recycle(root)
            return
        }
        val tracksHealth = hasAnchor(cfg, pkg)
        val detection = withContext(Dispatchers.Default) {
            NodeTree(AccessibilityNodeSource(root)).use { tree ->
                val hit = det.detect(pkg, tree.root)
                if (tracksHealth) health.record(pkg, det.anchorSeen(pkg, tree.root))
                hit
            }
        }
        val decision = d.decide(pkg, detection, sections, plan, grants, now, health.isHealthy(pkg))
        if (decision.action == EnforcementAction.NONE) return
        // Own job: the BACK/HOME we perform fires new events that cancel the debounced job.
        scope.launch { act(c, decision, plan, now) }
    }

    private suspend fun act(c: AppContainer, decision: EnforcementDecision, plan: Plan, now: Instant) {
        val surfaceId = decision.surfaceId ?: return
        when (decision.action) {
            EnforcementAction.NONE -> return
            EnforcementAction.BACK_AND_INTERCEPT -> performGlobalAction(GLOBAL_ACTION_BACK)
            EnforcementAction.HOME_AND_INTERCEPT -> performGlobalAction(GLOBAL_ACTION_HOME)
        }
        val today = now.atZone(c.zone).toLocalDate()
        val label = scheduleLabel(plan, decision.scheduleId, getString(R.string.enforcement_block_fallback_label))
        // The single place a real open is recorded (the in-app Intercept route records only its debug entry).
        runCatching { c.sessionRepo.recordIntercept(surfaceId, now) }
            .onFailure { e -> Log.w(TAG, "Could not record intercept", e) }
        val state = runCatching {
            InterceptStateBuilder.forOverlay(surfaceId, label, decision.strict, c.sessionRepo, today)
        }.getOrElse { e ->
            Log.w(TAG, "Could not count intercepts", e)
            InterceptUiState(surfaceId, label, opensToday = 0, opensBaseline = 0, strict = decision.strict)
        }
        val ov = overlay ?: return
        ov.show(
            state = state,
            onClose = {
                ov.hide()
                performGlobalAction(GLOBAL_ACTION_HOME)
            },
            onSwap = {
                ov.hide()
                runCatching { startActivity(launchIntent(this, ROUTE_SWAP)) }
                    .onFailure { e -> Log.w(TAG, "Could not open swap", e) }
            },
            // Stay in the app (messages etc.); the section keeps being hidden on the next event.
            onMessagesOnly = { ov.hide() },
        )
    }

    /** Hot reload: picks up a newer `container.detectorConfig` (DetectorConfigStore) on the next event. */
    private fun currentConfig(c: AppContainer): DetectorConfig {
        val latest = c.detectorConfig
        if (latest !== config) {
            config = latest
            detector = SurfaceDetector(latest)
            refreshObservedPackages()
        }
        return latest
    }

    /** Observe exactly the detector packages plus `app:<pkg>` blocks from the plan. */
    private fun refreshObservedPackages() {
        val c = container ?: return
        val cfg = config ?: c.detectorConfig
        val next = observedPackages(cfg, plan)
        if (next == observed) return
        observed = next
        val info = serviceInfo ?: return
        info.packageNames = next.toTypedArray()
        serviceInfo = info
    }

    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) node.recycle()
    }

    override fun onInterrupt() {
        overlay?.hide()
    }

    override fun onDestroy() {
        overlay?.hide()
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SteadyA11y"
        const val DEBOUNCE_MS = 250L
    }
}
