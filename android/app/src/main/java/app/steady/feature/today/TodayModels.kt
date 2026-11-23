package app.steady.feature.today

import app.steady.data.StudyTask
import app.steady.rules.BlockState
import app.steady.rules.Energy
import app.steady.rules.EnergyPlanner
import app.steady.rules.Plan
import app.steady.rules.RulesEngine
import app.steady.rules.UpcomingBlock
import app.steady.ui.Format
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class StripSegment(val fraction: Float, val isBlock: Boolean)

data class DayStrip(val segments: List<StripSegment>, val legend: List<String>, val nowFraction: Float?) {
    companion object {
        val DEFAULT_WINDOW_START: LocalTime = LocalTime.of(8, 0)

        /**
         * Lays today's blocks on a timeline from 8 am (or the first block, if earlier) to midnight.
         * Blocks that cross midnight are clipped at midnight; overlaps are merged.
         */
        fun build(blocks: List<UpcomingBlock>, date: LocalDate, zone: ZoneId, now: Instant? = null): DayStrip {
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
            val firstStart = blocks.minOfOrNull { it.startsAt }
            val defaultStart = date.atTime(DEFAULT_WINDOW_START).atZone(zone).toInstant()
            val windowStart = if (firstStart != null && firstStart.isBefore(defaultStart)) {
                maxOf(firstStart, date.atStartOfDay(zone).toInstant())
            } else defaultStart
            val total = Duration.between(windowStart, dayEnd).seconds.toFloat()
            if (total <= 0f) return DayStrip(emptyList(), emptyList(), null)

            val segments = mutableListOf<StripSegment>()
            var cursor = windowStart
            for (b in blocks.sortedBy { it.startsAt }) {
                val start = maxOf(b.startsAt, cursor)
                val end = minOf(b.endsAt, dayEnd)
                if (!end.isAfter(start)) continue
                if (start.isAfter(cursor)) segments += StripSegment(Duration.between(cursor, start).seconds / total, false)
                segments += StripSegment(Duration.between(start, end).seconds / total, true)
                cursor = end
            }
            if (dayEnd.isAfter(cursor)) segments += StripSegment(Duration.between(cursor, dayEnd).seconds / total, false)

            val legend = buildList {
                add(Format.time(windowStart.atZone(zone).toLocalTime()))
                blocks.forEach { add(Format.timeRange(it.startsAt.atZone(zone).toLocalTime(), it.endsAt.atZone(zone).toLocalTime())) }
                add("12 am")
            }
            val nowFraction = now?.takeIf { !it.isBefore(windowStart) && it.isBefore(dayEnd) }
                ?.let { Duration.between(windowStart, it).seconds / total }
            return DayStrip(segments, legend, nowFraction)
        }
    }
}

data class NextBlockUi(
    val scheduleId: String,
    val label: String,
    val timeRange: String,
    /** "in 12 min" or "on now · until 7 pm". */
    val status: String,
    val isActive: Boolean,
)

data class TodayUiState(
    val loading: Boolean = true,
    val dateLabel: String = "",
    val autoStartOn: Boolean = false,
    val energy: Energy? = null,
    val blockMinutes: Int = EnergyPlanner.blockMinutes(Energy.OKAY),
    val strip: DayStrip = DayStrip(emptyList(), emptyList(), null),
    val next: NextBlockUi? = null,
    val tasks: List<StudyTask> = emptyList(),
    val draftTitle: String = "",
    val draftStep: String = "",
) {
    val canAddTask: Boolean get() = draftTitle.isNotBlank()
}

object TodayStateBuilder {
    private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun build(
        plan: Plan,
        tasks: List<StudyTask>,
        energy: Energy?,
        now: Instant,
        rules: RulesEngine,
        zone: ZoneId,
    ): TodayUiState {
        val date = now.atZone(zone).toLocalDate()
        val blocks = rules.blocksOn(plan, date)
        val next = when (val st = rules.state(plan, now)) {
            is BlockState.Active -> {
                val label = plan.schedules.firstOrNull { it.id == st.scheduleId }?.label ?: "Focus block"
                NextBlockUi(
                    scheduleId = st.scheduleId,
                    label = label,
                    timeRange = range(st.startedAt, st.endsAt, zone),
                    status = "on now · until ${Format.time(st.endsAt.atZone(zone).toLocalTime())}",
                    isActive = true,
                )
            }
            BlockState.Idle -> rules.nextBlock(plan, now)?.let { b ->
                val startDate = b.startsAt.atZone(zone).toLocalDate()
                val dayPrefix = when (startDate) {
                    date -> ""
                    date.plusDays(1) -> "Tomorrow "
                    else -> "${Format.dayName(startDate.dayOfWeek.value)} "
                }
                NextBlockUi(
                    scheduleId = b.scheduleId,
                    label = b.label,
                    timeRange = dayPrefix + range(b.startsAt, b.endsAt, zone),
                    status = Format.countdown(Duration.between(now, b.startsAt)),
                    isActive = false,
                )
            }
        }
        return TodayUiState(
            loading = false,
            dateLabel = "Today · ${date.format(dateFmt)}",
            autoStartOn = plan.schedules.isNotEmpty(),
            energy = energy,
            blockMinutes = EnergyPlanner.blockMinutes(energy ?: Energy.OKAY),
            strip = DayStrip.build(blocks, date, zone, now),
            next = next,
            tasks = tasks,
        )
    }

    private fun range(start: Instant, end: Instant, zone: ZoneId) =
        Format.timeRange(start.atZone(zone).toLocalTime(), end.atZone(zone).toLocalTime())
}
