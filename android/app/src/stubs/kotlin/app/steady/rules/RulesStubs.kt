// TODO(phase12): remove stubs source set once core modules merged.
// Minimal stand-in for core/rules (Phase 2). Signatures match docs/CONTRACTS.md; bodies are simple.
package app.steady.rules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

@Serializable data class Plan(
    val version: Int = 1,
    val distractions: List<Surface>,
    val schedules: List<Schedule>,
    val keyHolder: KeyHolder? = null,
    val unlockPolicy: UnlockPolicy = UnlockPolicy(),
    val siteBlocks: List<String> = emptyList(),
)

@Serializable data class Surface(val id: String, val mode: SurfaceMode)

@Serializable enum class SurfaceMode {
    @SerialName("hide_section") HIDE_SECTION,
    @SerialName("block_app") BLOCK_APP,
}

@Serializable data class Schedule(
    val id: String,
    val label: String,
    val days: Set<Int>,
    val start: String,
    val end: String,
    val strict: Boolean = true,
)

@Serializable data class KeyHolder(val id: String, val name: String, val relation: Relation)

@Serializable enum class Relation {
    @SerialName("self") SELF,
    @SerialName("friend") FRIEND,
    @SerialName("parent") PARENT,
}

@Serializable data class UnlockPolicy(
    val waitSeconds: Int = 30,
    val requireReason: Boolean = true,
    val notifyKeyHolder: Boolean = true,
    val approvalMinutes: Int = 15,
)

object PlanCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun decode(json: String): Plan = this.json.decodeFromString(Plan.serializer(), json)
    fun encode(plan: Plan): String = json.encodeToString(Plan.serializer(), plan)
}

sealed interface BlockState {
    data object Idle : BlockState
    data class Active(
        val scheduleId: String,
        val strict: Boolean,
        val startedAt: Instant,
        val endsAt: Instant,
        val blocked: Set<Surface>,
    ) : BlockState
}

data class UpcomingBlock(val scheduleId: String, val label: String, val startsAt: Instant, val endsAt: Instant)

data class UnlockGrant(
    val id: String,
    val surfaceId: String?,
    val from: Instant,
    val until: Instant,
    val approvedBy: String?,
)

class RulesEngine(private val zone: ZoneId) {
    fun state(plan: Plan, now: Instant): BlockState {
        val today = now.atZone(zone).toLocalDate()
        val active = (blocksOn(plan, today.minusDays(1)) + blocksOn(plan, today))
            .firstOrNull { !now.isBefore(it.startsAt) && now.isBefore(it.endsAt) }
            ?: return BlockState.Idle
        val schedule = plan.schedules.first { it.id == active.scheduleId }
        return BlockState.Active(active.scheduleId, schedule.strict, active.startsAt, active.endsAt, plan.distractions.toSet())
    }

    fun nextBlock(plan: Plan, now: Instant): UpcomingBlock? {
        val today = now.atZone(zone).toLocalDate()
        return (0L..7L).asSequence()
            .flatMap { blocksOn(plan, today.plusDays(it)).asSequence() }
            .firstOrNull { it.startsAt.isAfter(now) }
    }

    fun isBlocked(plan: Plan, grants: List<UnlockGrant>, surfaceId: String, now: Instant): Boolean {
        val s = state(plan, now) as? BlockState.Active ?: return false
        if (!s.strict || s.blocked.none { it.id == surfaceId }) return false
        return grants.none { (it.surfaceId == null || it.surfaceId == surfaceId) && !now.isBefore(it.from) && now.isBefore(it.until) }
    }

    fun blocksOn(plan: Plan, date: LocalDate): List<UpcomingBlock> =
        plan.schedules.filter { date.dayOfWeek.value in it.days }.map { s ->
            val start = LocalTime.parse(s.start)
            val end = LocalTime.parse(s.end)
            val startsAt = date.atTime(start).atZone(zone).toInstant()
            val endDate = if (!end.isAfter(start)) date.plusDays(1) else date
            UpcomingBlock(s.id, s.label, startsAt, endDate.atTime(end).atZone(zone).toInstant())
        }.sortedBy { it.startsAt }
}

enum class UnlockStep { WAITING, NEEDS_REASON, READY, NEEDS_KEYHOLDER }

class UnlockFlow(private val policy: UnlockPolicy, private val hasKeyHolder: Boolean) {
    fun step(waitStartedAt: Instant, now: Instant, reason: String): UnlockStep = when {
        secondsLeft(waitStartedAt, now) > 0 -> UnlockStep.WAITING
        policy.requireReason && reason.count { !it.isWhitespace() } < 8 -> UnlockStep.NEEDS_REASON
        hasKeyHolder && policy.notifyKeyHolder -> UnlockStep.NEEDS_KEYHOLDER
        else -> UnlockStep.READY
    }

    fun secondsLeft(waitStartedAt: Instant, now: Instant): Int =
        (policy.waitSeconds - Duration.between(waitStartedAt, now).seconds).coerceAtLeast(0).toInt()

    fun selfGrant(surfaceId: String?, now: Instant, minutes: Int = policy.approvalMinutes): UnlockGrant =
        UnlockGrant(UUID.randomUUID().toString(), surfaceId, now, now.plusSeconds(minutes * 60L), null)
}

enum class Energy { LOW, OKAY, SHARP }

object EnergyPlanner {
    fun blockMinutes(energy: Energy): Int = when (energy) {
        Energy.LOW -> 15
        Energy.OKAY -> 25
        Energy.SHARP -> 40
    }
}
