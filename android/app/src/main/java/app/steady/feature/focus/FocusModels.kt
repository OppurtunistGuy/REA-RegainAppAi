package app.steady.feature.focus

import app.steady.data.StudyTask
import app.steady.feature.intercept.InterceptCopy
import app.steady.rules.BlockState
import app.steady.rules.Energy
import app.steady.rules.EnergyPlanner
import app.steady.rules.Plan
import app.steady.rules.Relation
import app.steady.rules.RulesEngine
import app.steady.rules.UnlockFlow
import app.steady.rules.UnlockGrant
import app.steady.rules.UnlockStep
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The block this Focus screen is timing, fixed when the screen opens. */
data class FocusBlock(
    /** When the user opened the screen; the saved [app.steady.learning.FocusSession] starts here. */
    val sessionStart: Instant,
    val endsAt: Instant,
    /** Length of the whole block, for the ring ("left of 25 min"). */
    val totalSeconds: Long,
    /** Active schedule id, or the route's id, or null for a manual block. */
    val scheduleId: String?,
    /** True when an Active schedule block is running (time runs to its `endsAt`). */
    val scheduled: Boolean,
    val strict: Boolean,
    /** Schedule label, "" for a manual block. */
    val label: String,
    /** Surface ids shown as "Blocked right now". */
    val blockedIds: List<String>,
)

object FocusBlockResolver {
    /**
     * An Active schedule block wins (time until its `endsAt`); otherwise a manual block sized by
     * today's energy check-in via [EnergyPlanner] (OKAY when there is no check-in).
     */
    fun resolve(plan: Plan, rules: RulesEngine, energy: Energy?, now: Instant, routeScheduleId: String?): FocusBlock =
        when (val st = rules.state(plan, now)) {
            is BlockState.Active -> FocusBlock(
                sessionStart = now,
                endsAt = st.endsAt,
                totalSeconds = Duration.between(st.startedAt, st.endsAt).seconds,
                scheduleId = st.scheduleId,
                scheduled = true,
                strict = st.strict,
                label = plan.schedules.firstOrNull { it.id == st.scheduleId }?.label.orEmpty(),
                // Keep the plan's order so chips read the way the user set them up.
                blockedIds = plan.distractions.map { it.id }.filter { id -> st.blocked.any { it.id == id } } +
                    st.blocked.map { it.id }.filter { id -> plan.distractions.none { it.id == id } },
            )
            BlockState.Idle -> {
                val minutes = EnergyPlanner.blockMinutes(energy ?: Energy.OKAY)
                FocusBlock(
                    sessionStart = now,
                    endsAt = now.plusSeconds(minutes * 60L),
                    totalSeconds = minutes * 60L,
                    scheduleId = routeScheduleId,
                    scheduled = false,
                    strict = false,
                    label = "",
                    blockedIds = plan.distractions.map { it.id },
                )
            }
        }
}

data class BlockedChip(val surfaceId: String, val name: String)

data class FocusUiState(
    val loading: Boolean = true,
    /** Schedule label (e.g. "Coaching block"), null for a manual block. */
    val blockTitle: String? = null,
    val scheduled: Boolean = false,
    val strict: Boolean = false,
    val remainingSeconds: Long = 0,
    val totalSeconds: Long = 0,
    val clockText: String = "00:00",
    val totalLabel: String = "",
    val spokenRemaining: String = "",
    val fraction: Float = 1f,
    /** "6:00 pm". */
    val endsAtLabel: String = "",
    val nowStep: String? = null,
    val nowTaskTitle: String? = null,
    val next: String? = null,
    val blocked: List<BlockedChip> = emptyList(),
    val canFinish: Boolean = true,
    val showEmergencyUnlock: Boolean = false,
    val waitSeconds: Int = 30,
    val requireReason: Boolean = true,
    val approvalMinutes: Int = 15,
    /** Key holder who gets told / asked, null when there is none (or it is the user). */
    val keyHolderName: String? = null,
    val unlock: UnlockSheetState = UnlockSheetState(),
    val unlockView: UnlockSheetView = UnlockSheetView(UnlockStep.WAITING, 0, ReasonHint.NONE, false, false),
    val grantedUntilLabel: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
) {
    val done: Boolean get() = !loading && remainingSeconds <= 0
}

/** Unlock policy view of a plan, shared by the state builder and the ViewModel. */
object FocusPolicy {
    /** A "self" key holder means there is nobody else to ask or tell. */
    fun keyHolderName(plan: Plan): String? = plan.keyHolder?.takeIf { it.relation != Relation.SELF }?.name

    fun unlockFlow(plan: Plan) = UnlockFlow(plan.unlockPolicy, hasKeyHolder = keyHolderName(plan) != null)

    fun reducer(plan: Plan) = UnlockSheetReducer(unlockFlow(plan), plan.unlockPolicy.requireReason)
}

object FocusStateBuilder {
    fun build(
        block: FocusBlock,
        plan: Plan,
        tasks: List<StudyTask>,
        now: Instant,
        zone: ZoneId,
        unlock: UnlockSheetState = UnlockSheetState(),
    ): FocusUiState {
        val remaining = FocusClock.remainingSeconds(now, block.endsAt)
        val open = tasks.filterNot { it.done }
        val current = open.firstOrNull()
        val policy = plan.unlockPolicy
        val keyHolder = FocusPolicy.keyHolderName(plan)
        val strictRunning = block.scheduled && block.strict && remaining > 0
        val grant = (unlock.phase as? UnlockPhase.Granted)?.until
        return FocusUiState(
            loading = false,
            blockTitle = if (block.scheduled) InterceptCopy.blockPhrase(block.label) else null,
            scheduled = block.scheduled,
            strict = block.strict,
            remainingSeconds = remaining,
            totalSeconds = block.totalSeconds,
            clockText = FocusClock.clock(remaining),
            totalLabel = FocusClock.durationLabel(block.totalSeconds),
            spokenRemaining = FocusClock.spokenRemaining(remaining),
            fraction = FocusClock.fraction(remaining, block.totalSeconds),
            endsAtLabel = FocusClock.wallTime(block.endsAt.atZone(zone).toLocalTime()),
            nowStep = current?.firstStep?.ifBlank { current.title },
            nowTaskTitle = current?.title,
            next = open.getOrNull(1)?.title,
            blocked = block.blockedIds.map { BlockedChip(it, InterceptCopy.surface(it).name) },
            // Strict blocks can only be finished once their time is up; everything else any time.
            canFinish = !strictRunning,
            showEmergencyUnlock = strictRunning,
            waitSeconds = policy.waitSeconds,
            requireReason = policy.requireReason,
            approvalMinutes = policy.approvalMinutes,
            keyHolderName = keyHolder?.takeIf { policy.notifyKeyHolder },
            unlock = unlock,
            unlockView = FocusPolicy.reducer(plan).view(unlock, now),
            grantedUntilLabel = grant?.let { FocusClock.wallTime(it.atZone(zone).toLocalTime()) },
        )
    }
}

/** Aggregates saved with the [app.steady.learning.FocusSession]. */
object FocusSessionMath {
    /**
     * Intercepts during the session, from per-day counters: today's count minus the count when the
     * session started, plus any later days' counts if the block crossed midnight.
     */
    fun intercepts(baselineOnStartDay: Int, startDayNow: Int, laterDays: List<Int> = emptyList()): Int =
        (startDayNow - baselineOnStartDay).coerceAtLeast(0) + laterDays.sumOf { it.coerceAtLeast(0) }

    /** Grants that started inside `[start, end)`. */
    fun unlocks(grants: List<UnlockGrant>, start: Instant, end: Instant): Int =
        grants.count { !it.from.isBefore(start) && it.from.isBefore(end) }
}
