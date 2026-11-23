package app.steady.feature.intercept

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.steady.data.PlanRepository
import app.steady.data.SessionRepository
import app.steady.rules.BlockState
import app.steady.rules.Plan
import app.steady.rules.RulesEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Number of previous days averaged for "down from N". */
const val BASELINE_DAYS = 7

object InterceptStateBuilder {
    /** Label of the active schedule at [now], or "" when no block is running. */
    fun scheduleLabel(plan: Plan, rules: RulesEngine, now: Instant): String =
        when (val st = rules.state(plan, now)) {
            is BlockState.Active -> plan.schedules.firstOrNull { it.id == st.scheduleId }?.label.orEmpty()
            BlockState.Idle -> ""
        }

    fun build(surfaceId: String, plan: Plan, rules: RulesEngine, now: Instant, opensToday: Int, previousDays: List<Int>) =
        InterceptUiState(
            surfaceId = surfaceId,
            scheduleLabel = scheduleLabel(plan, rules, now),
            opensToday = opensToday,
            opensBaseline = InterceptCopy.baseline(previousDays),
        )
}

/**
 * In-app intercept (Today's "Try opening Instagram"). Records the open like the overlay would, then
 * shows today's count against the usual over the previous [BASELINE_DAYS] days.
 */
class InterceptViewModel(
    private val surfaceId: String,
    planRepo: PlanRepository,
    private val sessionRepo: SessionRepository,
    private val rules: RulesEngine,
    zone: ZoneId,
    private val clock: Clock,
) : ViewModel() {
    private val openedAt: Instant = clock.instant()
    private val today: LocalDate = openedAt.atZone(zone).toLocalDate()

    init {
        viewModelScope.launch { sessionRepo.recordIntercept(surfaceId, openedAt) }
    }

    private val previousDays: Flow<List<Int>> =
        combine((1..BASELINE_DAYS).map { sessionRepo.interceptsOn(today.minusDays(it.toLong())) }) { it.toList() }

    val state: StateFlow<InterceptUiState> =
        combine(planRepo.plan, sessionRepo.interceptsOn(today), previousDays) { plan, todayCount, prev ->
            InterceptStateBuilder.build(surfaceId, plan, rules, openedAt, todayCount, prev)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InterceptUiState(surfaceId, "", 0, 0))
}
