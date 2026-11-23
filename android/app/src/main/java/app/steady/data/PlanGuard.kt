package app.steady.data

import app.steady.rules.BlockState
import app.steady.rules.Plan
import app.steady.rules.RulesEngine
import app.steady.rules.SurfaceMode
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Thrown by [GuardedPlanRepository.save] when an edit would weaken a strict block that is running. */
class PlanLockedException(val until: Instant, val reasons: List<PlanGuard.Weakening>) :
    IllegalStateException("Plan is locked until $until: $reasons")

/**
 * While a strict block is active, the plan may only get stronger until that block ends. Pure.
 *
 * Weakening = removing a distraction or moving it from BLOCK_APP to HIDE_SECTION; removing any
 * schedule; changing the running schedule (times, days, strictness) so it would end earlier or stop;
 * changing the key holder or the unlock policy. Adding distractions or schedules is always fine.
 */
object PlanGuard {
    enum class Weakening { DISTRACTION_REMOVED, DISTRACTION_SOFTENED, SCHEDULE_REMOVED, ACTIVE_BLOCK_CHANGED, KEY_HOLDER_CHANGED, UNLOCK_POLICY_CHANGED }

    sealed interface Verdict {
        data object Allowed : Verdict
        data class Locked(val until: Instant, val reasons: List<Weakening>) : Verdict
    }

    fun check(current: Plan, proposed: Plan, rules: RulesEngine, now: Instant): Verdict {
        val active = rules.state(current, now) as? BlockState.Active ?: return Verdict.Allowed
        if (!active.strict) return Verdict.Allowed
        val reasons = buildList {
            val next = proposed.distractions.associateBy { it.id }
            current.distractions.forEach { d ->
                val p = next[d.id]
                when {
                    p == null -> add(Weakening.DISTRACTION_REMOVED)
                    d.mode == SurfaceMode.BLOCK_APP && p.mode != SurfaceMode.BLOCK_APP -> add(Weakening.DISTRACTION_SOFTENED)
                }
            }
            val nextSchedules = proposed.schedules.map { it.id }.toSet()
            if (current.schedules.any { it.id !in nextSchedules }) add(Weakening.SCHEDULE_REMOVED)
            val after = rules.state(proposed, now) as? BlockState.Active
            val stillCovered = after != null && after.strict && !after.endsAt.isBefore(active.endsAt)
            if (!stillCovered) add(Weakening.ACTIVE_BLOCK_CHANGED)
            if (proposed.keyHolder != current.keyHolder) add(Weakening.KEY_HOLDER_CHANGED)
            if (proposed.unlockPolicy != current.unlockPolicy) add(Weakening.UNLOCK_POLICY_CHANGED)
        }.distinct()
        return if (reasons.isEmpty()) Verdict.Allowed else Verdict.Locked(active.endsAt, reasons)
    }
}

/**
 * [PlanRepository] decorator applying [PlanGuard] to every save: Setup edits and plans pulled from
 * the server alike (a weaker server plan waits until the block is over; the next sync applies it).
 */
class GuardedPlanRepository(
    private val delegate: PlanRepository,
    private val rules: () -> RulesEngine,
    private val clock: Clock,
) : PlanRepository {
    override val plan: Flow<Plan> get() = delegate.plan

    override suspend fun save(plan: Plan) {
        val current = delegate.plan.first()
        when (val v = PlanGuard.check(current, plan, rules(), clock.instant())) {
            PlanGuard.Verdict.Allowed -> delegate.save(plan)
            is PlanGuard.Verdict.Locked -> throw PlanLockedException(v.until, v.reasons)
        }
    }
}
