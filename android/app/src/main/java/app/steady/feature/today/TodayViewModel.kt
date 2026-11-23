package app.steady.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.steady.data.DayRepository
import app.steady.data.PlanRepository
import app.steady.rules.Energy
import app.steady.rules.RulesEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Emits the current time now and then every [periodMs]. */
fun tickerFlow(clock: Clock, periodMs: Long = 15_000): Flow<Instant> = flow {
    while (true) {
        emit(clock.instant())
        delay(periodMs)
    }
}

private data class Draft(val title: String = "", val step: String = "")

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    planRepo: PlanRepository,
    private val dayRepo: DayRepository,
    private val rules: RulesEngine,
    private val zone: ZoneId,
    now: Flow<Instant>,
) : ViewModel() {
    private val draft = MutableStateFlow(Draft())
    private val nowShared = now.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    private val today = nowShared.map { it.atZone(zone).toLocalDate() }.distinctUntilChanged()
    private val energy = today.flatMapLatest { dayRepo.energyOn(it) }

    val state: StateFlow<TodayUiState> =
        combine(planRepo.plan, dayRepo.tasks, energy, nowShared, draft) { plan, tasks, e, t, d ->
            TodayStateBuilder.build(plan, tasks, e, t, rules, zone).copy(draftTitle = d.title, draftStep = d.step)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    fun setEnergy(e: Energy) {
        viewModelScope.launch { dayRepo.setEnergy(currentDate(), e) }
    }

    fun setDraftTitle(v: String) = draft.update { it.copy(title = v.take(80)) }
    fun setDraftStep(v: String) = draft.update { it.copy(step = v.take(80)) }

    fun addTask() {
        val d = draft.value
        if (d.title.isBlank()) return
        draft.value = Draft()
        viewModelScope.launch {
            val step = d.step.ifBlank { "Open your books to the right page" }
            dayRepo.addTask(d.title, step)
        }
    }

    fun toggleTask(id: String, done: Boolean) {
        viewModelScope.launch { dayRepo.setTaskDone(id, done) }
    }

    fun deleteTask(id: String) {
        viewModelScope.launch { dayRepo.deleteTask(id) }
    }

    /** Date of the latest tick (the ticker is running whenever the screen is visible). */
    private fun currentDate() = (nowShared.replayCache.lastOrNull() ?: Instant.now()).atZone(zone).toLocalDate()
}
