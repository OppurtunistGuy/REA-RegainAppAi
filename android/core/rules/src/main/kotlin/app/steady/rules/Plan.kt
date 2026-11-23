package app.steady.rules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The single rules document shared by Android, backend and extension (`shared/schema/plan.schema.json`). */
@Serializable
data class Plan(
    val version: Int = 1,
    val distractions: List<Surface>,
    val schedules: List<Schedule>,
    val keyHolder: KeyHolder? = null,
    val unlockPolicy: UnlockPolicy = UnlockPolicy(),
    val siteBlocks: List<String> = emptyList(),
)

/**
 * A distracting surface.
 * id: "instagram.reels" | "youtube.shorts" | "snapchat.spotlight" | "facebook.reels"
 *     | "app:<packageName>" | "site:<domain>"
 */
@Serializable
data class Surface(val id: String, val mode: SurfaceMode)

@Serializable
enum class SurfaceMode {
    @SerialName("hide_section") HIDE_SECTION,
    @SerialName("block_app") BLOCK_APP,
}

@Serializable
data class Schedule(
    val id: String,
    val label: String,
    /** ISO day of week, 1 = Monday … 7 = Sunday. The day the block *starts*. */
    val days: Set<Int>,
    /** "HH:mm" local time, inclusive. */
    val start: String,
    /** "HH:mm" local time, exclusive; end < start crosses midnight. end == start means 24 hours. */
    val end: String,
    val strict: Boolean = true,
)

@Serializable
data class KeyHolder(val id: String, val name: String, val relation: Relation)

@Serializable
enum class Relation {
    @SerialName("self") SELF,
    @SerialName("friend") FRIEND,
    @SerialName("parent") PARENT,
}

@Serializable
data class UnlockPolicy(
    val waitSeconds: Int = 30,
    val requireReason: Boolean = true,
    val notifyKeyHolder: Boolean = true,
    val approvalMinutes: Int = 15,
)
