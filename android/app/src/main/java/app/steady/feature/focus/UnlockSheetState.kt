package app.steady.feature.focus

import app.steady.rules.UnlockFlow
import app.steady.rules.UnlockStep
import java.time.Instant

/** Where the emergency unlock sheet is. */
sealed interface UnlockPhase {
    data object Closed : UnlockPhase
    /** Wait + reason form. */
    data object Form : UnlockPhase
    data object Sending : UnlockPhase
    /** Asked the key holder; Phase 11 polls the request and adds the grant on approval. */
    data class Pending(val requestId: String) : UnlockPhase
    data class Granted(val until: Instant) : UnlockPhase
    data object Failed : UnlockPhase
}

data class UnlockSheetState(
    val phase: UnlockPhase = UnlockPhase.Closed,
    val waitStartedAt: Instant = Instant.EPOCH,
    val reason: String = "",
    /** null = everything. */
    val surfaceId: String? = null,
    /** Survives closing the sheet, so reopening shows the pending request instead of a new wait. */
    val pendingRequestId: String? = null,
) {
    val isOpen: Boolean get() = phase != UnlockPhase.Closed
}

sealed interface UnlockAction {
    data class Open(val now: Instant) : UnlockAction
    data class ReasonChanged(val text: String) : UnlockAction
    data class SurfaceChosen(val surfaceId: String?) : UnlockAction
    /** User tapped the submit button; becomes [UnlockPhase.Sending] only if allowed at [now]. */
    data class Submit(val now: Instant) : UnlockAction
    data class Requested(val requestId: String) : UnlockAction
    data class Granted(val until: Instant) : UnlockAction
    data object Failed : UnlockAction
    data object Dismiss : UnlockAction
}

enum class ReasonHint { NONE, OPTIONAL, SHORT, OK }

/** What the sheet shows at a given moment. */
data class UnlockSheetView(
    val step: UnlockStep,
    val secondsLeft: Int,
    val reasonHint: ReasonHint,
    val canSubmit: Boolean,
    /** True when submitting asks the key holder instead of granting right away. */
    val asksKeyHolder: Boolean,
)

/**
 * Pure reducer around [UnlockFlow]: wait countdown, live reason validation, and the two submit paths
 * (NEEDS_KEYHOLDER → ask, READY → self grant). Side effects run in [FocusViewModel].
 */
class UnlockSheetReducer(
    private val flow: UnlockFlow,
    private val requireReason: Boolean,
) {
    fun reduce(s: UnlockSheetState, a: UnlockAction): UnlockSheetState = when (a) {
        is UnlockAction.Open -> when {
            s.isOpen -> s
            s.pendingRequestId != null -> s.copy(phase = UnlockPhase.Pending(s.pendingRequestId))
            else -> UnlockSheetState(phase = UnlockPhase.Form, waitStartedAt = a.now, surfaceId = s.surfaceId)
        }
        is UnlockAction.ReasonChanged ->
            if (s.phase == UnlockPhase.Form || s.phase == UnlockPhase.Failed) {
                s.copy(reason = a.text.take(MAX_REASON), phase = UnlockPhase.Form)
            } else s
        is UnlockAction.SurfaceChosen -> if (s.phase == UnlockPhase.Form) s.copy(surfaceId = a.surfaceId) else s
        is UnlockAction.Submit ->
            if ((s.phase == UnlockPhase.Form || s.phase == UnlockPhase.Failed) && view(s, a.now).canSubmit) {
                s.copy(phase = UnlockPhase.Sending)
            } else s
        is UnlockAction.Requested ->
            if (s.phase == UnlockPhase.Sending) s.copy(phase = UnlockPhase.Pending(a.requestId), pendingRequestId = a.requestId) else s
        is UnlockAction.Granted ->
            if (s.phase == UnlockPhase.Sending) s.copy(phase = UnlockPhase.Granted(a.until), reason = "") else s
        UnlockAction.Failed -> if (s.phase == UnlockPhase.Sending) s.copy(phase = UnlockPhase.Failed) else s
        UnlockAction.Dismiss -> s.copy(phase = UnlockPhase.Closed, reason = "")
    }

    fun view(s: UnlockSheetState, now: Instant): UnlockSheetView {
        val step = flow.step(s.waitStartedAt, now, s.reason)
        val hint = when {
            !requireReason -> ReasonHint.OPTIONAL
            UnlockFlow.isReasonValid(s.reason) -> ReasonHint.OK
            s.reason.isBlank() -> ReasonHint.NONE
            else -> ReasonHint.SHORT
        }
        return UnlockSheetView(
            step = step,
            secondsLeft = flow.secondsLeft(s.waitStartedAt, now),
            reasonHint = hint,
            canSubmit = step == UnlockStep.READY || step == UnlockStep.NEEDS_KEYHOLDER,
            // Same answer during the wait, so the button is labelled for the path it will take.
            asksKeyHolder = willAskKeyHolder,
        )
    }

    /** The path does not depend on time or reason: probe [UnlockFlow] once with a finished wait and a valid reason. */
    private val willAskKeyHolder: Boolean =
        flow.step(Instant.EPOCH, Instant.EPOCH.plusSeconds(LONG_AGO), "x".repeat(UnlockFlow.MIN_REASON_CHARS)) ==
            UnlockStep.NEEDS_KEYHOLDER

    companion object {
        const val MAX_REASON = 200
        private const val LONG_AGO = 86_400L
    }
}
