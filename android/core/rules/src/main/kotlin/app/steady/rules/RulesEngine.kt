package app.steady.rules

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

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
    /** null = everything */
    val surfaceId: String?,
    val from: Instant,
    val until: Instant,
    val approvedBy: String?,
) {
    /** Valid in [from, until). */
    fun coversAt(surfaceId: String, now: Instant): Boolean =
        (this.surfaceId == null || this.surfaceId == surfaceId) && !now.isBefore(from) && now.isBefore(until)
}

/**
 * Evaluates a [Plan]'s schedules in local time of [zone].
 *
 * - Start is inclusive, end is exclusive.
 * - A schedule whose end < start crosses midnight and belongs to the day it starts.
 *   end == start is treated as a full 24 hours.
 * - Local times are resolved through [ZoneId] so DST gaps/overlaps are handled by java.time
 *   (a start inside a spring-forward gap is shifted forward by the gap length).
 * - If several schedules are active: strict wins over non-strict, then the one ending later, then lowest id.
 */
class RulesEngine(private val zone: ZoneId) {

    private data class Occurrence(val schedule: Schedule, val start: Instant, val end: Instant)

    fun state(plan: Plan, now: Instant): BlockState {
        val today = now.atZone(zone).toLocalDate()
        val active = sequenceOf(today.minusDays(1), today)
            .flatMap { occurrencesOn(plan, it) }
            .filter { !now.isBefore(it.start) && now.isBefore(it.end) }
            .sortedWith(activeOrder)
            .firstOrNull() ?: return BlockState.Idle
        return BlockState.Active(
            scheduleId = active.schedule.id,
            strict = active.schedule.strict,
            startedAt = active.start,
            endsAt = active.end,
            blocked = blockedSurfaces(plan),
        )
    }

    /** Next block whose start is strictly after [now], looking up to 8 days ahead. */
    fun nextBlock(plan: Plan, now: Instant): UpcomingBlock? {
        val today = now.atZone(zone).toLocalDate()
        return (0L..LOOKAHEAD_DAYS).asSequence()
            .flatMap { occurrencesOn(plan, today.plusDays(it)) }
            .filter { it.start.isAfter(now) }
            .sortedWith(compareBy<Occurrence> { it.start }.then(activeOrder))
            .firstOrNull()
            ?.toUpcoming()
    }

    fun isBlocked(plan: Plan, grants: List<UnlockGrant>, surfaceId: String, now: Instant): Boolean {
        if (!isListed(plan, surfaceId)) return false
        if (state(plan, now) !is BlockState.Active) return false
        return grants.none { it.coversAt(surfaceId, now) }
    }

    /** Blocks that start on [date] (local), ordered by start then id. */
    fun blocksOn(plan: Plan, date: LocalDate): List<UpcomingBlock> =
        occurrencesOn(plan, date)
            .sortedWith(compareBy<Occurrence> { it.start }.thenBy { it.schedule.id })
            .map { it.toUpcoming() }
            .toList()

    /** Distractions plus `siteBlocks` as `site:<domain>` surfaces (BLOCK_APP), de-duplicated by id. */
    fun blockedSurfaces(plan: Plan): Set<Surface> {
        val listed = plan.distractions.distinctBy { it.id }
        val ids = listed.mapTo(HashSet()) { it.id }
        val sites = plan.siteBlocks.map { "site:${it.lowercase()}" }.filter { it !in ids }.distinct()
            .map { Surface(it, SurfaceMode.BLOCK_APP) }
        return (listed + sites).toCollection(LinkedHashSet())
    }

    private fun isListed(plan: Plan, surfaceId: String): Boolean {
        if (plan.distractions.any { it.id == surfaceId }) return true
        if (!surfaceId.startsWith(SITE_PREFIX)) return false
        val host = surfaceId.removePrefix(SITE_PREFIX).lowercase()
        val domains = plan.siteBlocks.map { it.lowercase() } +
            plan.distractions.filter { it.id.startsWith(SITE_PREFIX) }.map { it.id.removePrefix(SITE_PREFIX).lowercase() }
        return domains.any { host == it || host.endsWith(".$it") }
    }

    private fun occurrencesOn(plan: Plan, date: LocalDate): Sequence<Occurrence> {
        val dow = date.dayOfWeek.value
        return plan.schedules.asSequence()
            .filter { dow in it.days }
            .map { s ->
                val startT = LocalTime.parse(s.start)
                val endT = LocalTime.parse(s.end)
                val endDate = if (endT.isAfter(startT)) date else date.plusDays(1)
                Occurrence(
                    schedule = s,
                    start = ZonedDateTime.of(date, startT, zone).toInstant(),
                    end = ZonedDateTime.of(endDate, endT, zone).toInstant(),
                )
            }
            .filter { it.end.isAfter(it.start) }
    }

    private fun Occurrence.toUpcoming() = UpcomingBlock(schedule.id, schedule.label, start, end)

    private val activeOrder: Comparator<Occurrence> =
        compareByDescending<Occurrence> { it.schedule.strict }
            .thenByDescending { it.end }
            .thenBy { it.schedule.id }

    companion object {
        const val LOOKAHEAD_DAYS = 8L
        private const val SITE_PREFIX = "site:"
    }
}
