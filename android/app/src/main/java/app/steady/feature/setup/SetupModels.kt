package app.steady.feature.setup

import app.steady.rules.KeyHolder
import app.steady.rules.Plan
import app.steady.rules.Relation
import app.steady.rules.Schedule
import app.steady.rules.Surface
import app.steady.rules.SurfaceMode
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class DistractionOption(val surfaceId: String, val label: String, val mode: SurfaceMode)

object SetupDefaults {
    /** Short-video sections are hidden inside the app; whole apps are blocked. */
    val distractions = listOf(
        DistractionOption("instagram.reels", "Instagram Reels", SurfaceMode.HIDE_SECTION),
        DistractionOption("youtube.shorts", "YouTube Shorts", SurfaceMode.HIDE_SECTION),
        DistractionOption("snapchat.spotlight", "Snapchat Spotlight", SurfaceMode.HIDE_SECTION),
        DistractionOption("facebook.reels", "Facebook Reels", SurfaceMode.HIDE_SECTION),
        DistractionOption("app:com.pubg.imobile", "BGMI", SurfaceMode.BLOCK_APP),
        DistractionOption("app:com.android.chrome", "Chrome", SurfaceMode.BLOCK_APP),
    )

    val defaultDistractions = setOf("instagram.reels", "youtube.shorts")

    const val COACHING_ID = "preset.coaching"
    const val NIGHT_ID = "preset.night"

    /** "Coaching 4–7 pm Mon–Fri" and "Night study 9–11 pm daily". */
    val presets = listOf(
        Schedule(COACHING_ID, "Coaching", days = setOf(1, 2, 3, 4, 5), start = "16:00", end = "19:00"),
        Schedule(NIGHT_ID, "Night study", days = setOf(1, 2, 3, 4, 5, 6, 7), start = "21:00", end = "23:00"),
    )
}

/** Editor state for a custom schedule. [id] null = new. */
data class ScheduleDraft(
    val id: String? = null,
    val label: String = "",
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    val start: LocalTime = LocalTime.of(10, 0),
    val end: LocalTime = LocalTime.of(12, 0),
) {
    val isValid: Boolean get() = label.isNotBlank() && days.isNotEmpty() && start != end

    fun toSchedule(newId: () -> String): Schedule =
        Schedule(id ?: newId(), label.trim(), days, start.format(HHMM), end.format(HHMM))

    companion object {
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun from(s: Schedule) = ScheduleDraft(s.id, s.label, s.days, LocalTime.parse(s.start), LocalTime.parse(s.end))
    }
}

/** Everything the user picked on Setup. */
data class SetupSelection(
    val distractionIds: Set<String>,
    val schedules: List<Schedule>,
    val relation: Relation,
    val keyHolderName: String,
)

object PlanBuilder {
    /**
     * Builds the next Plan from Setup choices. Keeps [previous]'s unlock policy and site blocks,
     * bumps the version, and keeps the key holder id stable if the person did not change.
     * "Me" as key holder means no external key holder: the 30-second wait + typed reason applies.
     */
    fun build(selection: SetupSelection, previous: Plan, newId: () -> String): Plan {
        val surfaces = SetupDefaults.distractions
            .filter { it.surfaceId in selection.distractionIds }
            .map { Surface(it.surfaceId, it.mode) } +
            // Keep surfaces from an existing plan that Setup does not list (e.g. "site:" added elsewhere).
            previous.distractions.filter { s -> SetupDefaults.distractions.none { it.surfaceId == s.id } }

        val keyHolder = when (selection.relation) {
            Relation.SELF -> null
            else -> {
                val name = selection.keyHolderName.trim()
                val prev = previous.keyHolder
                val id = if (prev != null && prev.relation == selection.relation && prev.name == name) prev.id else newId()
                KeyHolder(id, name, selection.relation)
            }
        }
        return Plan(
            version = previous.version + 1,
            distractions = surfaces,
            schedules = selection.schedules,
            keyHolder = keyHolder,
            unlockPolicy = previous.unlockPolicy.copy(notifyKeyHolder = keyHolder != null),
            siteBlocks = previous.siteBlocks,
        )
    }
}
