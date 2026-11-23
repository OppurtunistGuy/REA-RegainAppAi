package app.steady.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.steady.data.EmptyPlan
import app.steady.data.EntitlementRepository
import app.steady.data.PlanRepository
import app.steady.data.isConfigured
import app.steady.feature.paywall.FreeTier
import app.steady.rules.Plan
import app.steady.rules.Relation
import app.steady.rules.Schedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class SetupUiState(
    val loading: Boolean = true,
    val isEdit: Boolean = false,
    val distractions: List<DistractionOption> = SetupDefaults.distractions,
    val selectedDistractions: Set<String> = SetupDefaults.defaultDistractions,
    val schedules: List<Schedule> = SetupDefaults.presets,
    val selectedSchedules: Set<String> = SetupDefaults.presets.map { it.id }.toSet(),
    val relation: Relation = Relation.SELF,
    val keyHolderName: String = "",
    val editor: ScheduleDraft? = null,
    val scheduleLimit: Int? = FreeTier.SCHEDULE_LIMIT,
    val limitHit: Boolean = false,
    val saving: Boolean = false,
    val saved: Boolean = false,
) {
    val needsName: Boolean get() = relation != Relation.SELF
    val canSave: Boolean
        get() = !loading && !saving && selectedDistractions.isNotEmpty() && selectedSchedules.isNotEmpty() &&
            (!needsName || keyHolderName.isNotBlank())

    fun isPreset(id: String) = SetupDefaults.presets.any { it.id == id }
}

class SetupViewModel(
    private val planRepo: PlanRepository,
    entitlements: EntitlementRepository,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    private var previous: Plan = EmptyPlan

    /** null = unlimited. */
    private val limit: StateFlow<Int?> = entitlements.entitlement
        .map(FreeTier::scheduleLimit)
        .stateIn(viewModelScope, SharingStarted.Eagerly, FreeTier.SCHEDULE_LIMIT)

    init {
        viewModelScope.launch {
            launch { limit.collect { l -> _state.update { it.copy(scheduleLimit = l) } } }
            val plan = planRepo.plan.first()
            previous = plan
            _state.update { s ->
                if (!plan.isConfigured) {
                    s.copy(loading = false)
                } else {
                    val custom = plan.schedules.filter { p -> SetupDefaults.presets.none { it.id == p.id } }
                    s.copy(
                        loading = false,
                        isEdit = true,
                        selectedDistractions = plan.distractions.map { it.id }.toSet(),
                        // Presets keep their edited times if the plan changed them.
                        schedules = SetupDefaults.presets.map { pr -> plan.schedules.firstOrNull { it.id == pr.id } ?: pr } + custom,
                        selectedSchedules = plan.schedules.map { it.id }.toSet(),
                        relation = plan.keyHolder?.relation ?: Relation.SELF,
                        keyHolderName = plan.keyHolder?.name.orEmpty(),
                    )
                }
            }
        }
    }

    fun toggleDistraction(id: String) = _state.update {
        it.copy(selectedDistractions = it.selectedDistractions.toggle(id))
    }

    fun toggleSchedule(id: String) = _state.update { s ->
        val selecting = id !in s.selectedSchedules
        val max = s.scheduleLimit
        if (selecting && max != null && s.selectedSchedules.size >= max) s.copy(limitHit = true)
        else s.copy(selectedSchedules = s.selectedSchedules.toggle(id), limitHit = false)
    }

    fun setRelation(relation: Relation) = _state.update { it.copy(relation = relation) }
    fun setKeyHolderName(name: String) = _state.update { it.copy(keyHolderName = name.take(40)) }

    fun newSchedule() = _state.update { it.copy(editor = ScheduleDraft()) }
    fun editSchedule(id: String) = _state.update { s ->
        s.copy(editor = s.schedules.firstOrNull { it.id == id }?.let(ScheduleDraft::from))
    }
    fun updateDraft(draft: ScheduleDraft) = _state.update { it.copy(editor = draft) }
    fun cancelDraft() = _state.update { it.copy(editor = null) }

    fun saveDraft() = _state.update { s ->
        val draft = s.editor?.takeIf { it.isValid } ?: return@update s
        val schedule = draft.toSchedule { "custom.${newId()}" }
        val exists = s.schedules.any { it.id == schedule.id }
        val schedules = if (exists) s.schedules.map { if (it.id == schedule.id) schedule else it } else s.schedules + schedule
        val max = s.scheduleLimit
        val canSelect = exists || max == null || s.selectedSchedules.size < max
        s.copy(
            schedules = schedules,
            selectedSchedules = if (canSelect) s.selectedSchedules + schedule.id else s.selectedSchedules,
            limitHit = !canSelect,
            editor = null,
        )
    }

    fun deleteSchedule(id: String) = _state.update { s ->
        if (s.isPreset(id)) s
        else s.copy(schedules = s.schedules.filterNot { it.id == id }, selectedSchedules = s.selectedSchedules - id, editor = null)
    }

    fun selection(s: SetupUiState = _state.value) = SetupSelection(
        distractionIds = s.selectedDistractions,
        schedules = s.schedules.filter { it.id in s.selectedSchedules },
        relation = s.relation,
        keyHolderName = s.keyHolderName,
    )

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val plan = PlanBuilder.build(selection(s), previous, newId)
            planRepo.save(plan)
            previous = plan
            _state.update { it.copy(saving = false, saved = true) }
        }
    }

    fun consumeSaved() = _state.update { it.copy(saved = false) }
}

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item
