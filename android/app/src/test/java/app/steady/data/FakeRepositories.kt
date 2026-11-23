package app.steady.data

import app.steady.rules.Energy
import app.steady.rules.Plan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate

class FakePlanRepository(initial: Plan = EmptyPlan) : PlanRepository {
    val current = MutableStateFlow(initial)
    val saved = mutableListOf<Plan>()
    override val plan: Flow<Plan> = current
    override suspend fun save(plan: Plan) {
        saved += plan
        current.value = plan
    }
}

class FakeEntitlements(features: Set<String> = emptySet()) : EntitlementRepository {
    override val entitlement: Flow<Entitlement> =
        MutableStateFlow(Entitlement(if (features.isEmpty()) Tier.FREE else Tier.PLUS, features))
}

class FakeDayRepository(private val now: Instant = Instant.EPOCH) : DayRepository {
    private var n = 0
    val taskState = MutableStateFlow<List<StudyTask>>(emptyList())
    val energyState = MutableStateFlow<Map<LocalDate, Energy>>(emptyMap())
    override val tasks: Flow<List<StudyTask>> = taskState
    override suspend fun addTask(title: String, firstStep: String): StudyTask {
        val t = StudyTask("t${++n}", title, firstStep, false, now)
        taskState.update { it + t }
        return t
    }
    override suspend fun setTaskDone(id: String, done: Boolean) =
        taskState.update { l -> l.map { if (it.id == id) it.copy(done = done) else it } }
    override suspend fun deleteTask(id: String) = taskState.update { l -> l.filterNot { it.id == id } }
    override fun energyOn(date: LocalDate): Flow<Energy?> = energyState.map { it[date] }
    override suspend fun setEnergy(date: LocalDate, energy: Energy) = energyState.update { it + (date to energy) }
}
