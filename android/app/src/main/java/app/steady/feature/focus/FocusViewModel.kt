package app.steady.feature.focus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.steady.data.DayRepository
import app.steady.data.EmptyPlan
import app.steady.data.PlanRepository
import app.steady.data.SessionRepository
import app.steady.data.UnlockRepository
import app.steady.learning.FocusSession
import app.steady.rules.Plan
import app.steady.rules.RulesEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Emits now, then on every whole second of [clock]. */
fun secondTicker(clock: Clock): Flow<Instant> = flow {
    while (true) {
        val now = clock.instant()
        emit(now)
        delay(1000L - (now.toEpochMilli() % 1000L))
    }
}

private data class Progress(val saving: Boolean = false, val saved: Boolean = false)

class FocusViewModel(
    private val planRepo: PlanRepository,
    private val dayRepo: DayRepository,
    private val sessionRepo: SessionRepository,
    private val unlockRepo: UnlockRepository,
    private val rules: RulesEngine,
    private val zone: ZoneId,
    private val clock: Clock,
    private val routeScheduleId: String?,
    ticks: Flow<Instant> = secondTicker(clock),
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val block = MutableStateFlow<FocusBlock?>(null)
    private val sheet = MutableStateFlow(UnlockSheetState())
    private val progress = MutableStateFlow(Progress())
    private var interceptBaseline = 0
    /** Latest plan, so sheet actions reduce synchronously and keep typing order. */
    private val plan: StateFlow<Plan?> = planRepo.plan.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            val now = clock.instant()
            val date = now.atZone(zone).toLocalDate()
            val current = plan.filterNotNull().first()
            val energy = dayRepo.energyOn(date).first()
            interceptBaseline = sessionRepo.interceptsOn(date).first()
            block.value = FocusBlockResolver.resolve(current, rules, energy, now, routeScheduleId)
        }
    }

    val state: StateFlow<FocusUiState> =
        combine(block, plan.filterNotNull(), dayRepo.tasks, ticks, combine(sheet, progress, ::Pair)) { b, pl, tasks, now, (s, p) ->
            if (b == null) FocusUiState()
            else FocusStateBuilder.build(b, pl, tasks, now, zone, s).copy(saving = p.saving, saved = p.saved)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FocusUiState())

    // ---- Emergency unlock ----

    fun openUnlock() = dispatch(UnlockAction.Open(clock.instant()))
    fun dismissUnlock() = dispatch(UnlockAction.Dismiss)
    fun setReason(text: String) = dispatch(UnlockAction.ReasonChanged(text))
    fun chooseSurface(surfaceId: String?) = dispatch(UnlockAction.SurfaceChosen(surfaceId))

    fun submitUnlock() {
        val plan = plan.value ?: return
        val reducer = FocusPolicy.reducer(plan)
        val now = clock.instant()
        val before = sheet.value
        val view = reducer.view(before, now)
        val after = reducer.reduce(before, UnlockAction.Submit(now))
        if (after.phase != UnlockPhase.Sending) return
        sheet.value = after
        viewModelScope.launch {
            val result = try {
                if (view.asksKeyHolder) {
                    UnlockAction.Requested(unlockRepo.requestFromKeyHolder(after.surfaceId, after.reason.trim()))
                } else {
                    val grant = FocusPolicy.unlockFlow(plan).selfGrant(after.surfaceId, now)
                    unlockRepo.add(grant)
                    UnlockAction.Granted(grant.until)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UnlockAction.Failed
            }
            sheet.update { reducer.reduce(it, result) }
        }
    }

    private fun dispatch(action: UnlockAction) {
        val reducer = FocusPolicy.reducer(plan.value ?: EmptyPlan)
        sheet.update { reducer.reduce(it, action) }
    }

    // ---- Finish ----

    /** Saves the [FocusSession] (aggregate counts only). The screen navigates to Recap once `saved`. */
    fun finish() {
        val b = block.value ?: return
        if (progress.value.saving || progress.value.saved) return
        // Strict blocks end on time; before that the screen offers Emergency unlock instead.
        if (b.scheduled && b.strict && FocusClock.remainingSeconds(clock.instant(), b.endsAt) > 0) return
        progress.value = Progress(saving = true)
        viewModelScope.launch {
            val end = clock.instant()
            val startDate = b.sessionStart.atZone(zone).toLocalDate()
            val endDate = end.atZone(zone).toLocalDate()
            val startDayNow = sessionRepo.interceptsOn(startDate).first()
            val laterDays = generateSequence(startDate.plusDays(1)) { it.plusDays(1) }
                .takeWhile { !it.isAfter(endDate) }
                .map { sessionRepo.interceptsOn(it) }
                .toList()
                .map { it.first() }
            val session = FocusSession(
                id = idGen(),
                scheduleId = b.scheduleId,
                start = b.sessionStart,
                end = end,
                intercepts = FocusSessionMath.intercepts(interceptBaseline, startDayNow, laterDays),
                unlocks = FocusSessionMath.unlocks(unlockRepo.grants.first(), b.sessionStart, end),
            )
            sessionRepo.add(session)
            progress.value = Progress(saving = false, saved = true)
        }
    }
}
