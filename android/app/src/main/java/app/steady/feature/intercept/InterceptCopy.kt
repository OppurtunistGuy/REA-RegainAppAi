package app.steady.feature.intercept

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Friendly names for a surface id (CONTRACTS: "instagram.reels" | "youtube.shorts" |
 * "snapchat.spotlight" | "facebook.reels" | "app:<packageName>" | "site:<domain>").
 *
 * @property name what the user opened ("Reels", "BGMI", "youtube.com").
 * @property appName the app that holds it ("Instagram"), used for "Close Instagram".
 * @property plural whether [name] takes "stay" ("Reels stay hidden") rather than "stays".
 * @property isSection true when only a section is hidden and the rest of the app (messages) still works.
 */
data class SurfaceCopy(val name: String, val appName: String, val plural: Boolean, val isSection: Boolean)

/** Pure copy decisions for the intercept sheet. Sentences themselves live in `strings_focus.xml`. */
object InterceptCopy {
    private val sections = mapOf(
        "instagram.reels" to SurfaceCopy("Reels", "Instagram", plural = true, isSection = true),
        "youtube.shorts" to SurfaceCopy("Shorts", "YouTube", plural = true, isSection = true),
        "snapchat.spotlight" to SurfaceCopy("Spotlight", "Snapchat", plural = false, isSection = true),
        "facebook.reels" to SurfaceCopy("Facebook Reels", "Facebook", plural = true, isSection = true),
    )

    /** Package names we can label without PackageManager (works in the overlay and in JVM tests). */
    private val knownApps = mapOf(
        "com.pubg.imobile" to "BGMI",
        "com.android.chrome" to "Chrome",
        "com.instagram.android" to "Instagram",
        "com.google.android.youtube" to "YouTube",
        "com.snapchat.android" to "Snapchat",
        "com.facebook.katana" to "Facebook",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.twitter.android" to "X",
        "com.reddit.frontpage" to "Reddit",
        "com.netflix.mediaclient" to "Netflix",
        "in.mohalla.sharechat" to "ShareChat",
        "com.dts.freefireth" to "Free Fire",
    )

    private val genericSegments = setOf("android", "app", "mobile", "client", "free", "lite", "com", "in", "org")

    fun surface(surfaceId: String): SurfaceCopy {
        sections[surfaceId]?.let { return it }
        return when {
            surfaceId.startsWith("app:") -> {
                val label = appLabel(surfaceId.removePrefix("app:"))
                SurfaceCopy(label, label, plural = false, isSection = false)
            }
            surfaceId.startsWith("site:") -> {
                val domain = surfaceId.removePrefix("site:").removePrefix("www.").ifBlank { "this site" }
                SurfaceCopy(domain, domain, plural = false, isSection = false)
            }
            // A future section detector, e.g. "tiktok.foryou": app before the dot, section after.
            surfaceId.contains('.') -> SurfaceCopy(
                name = surfaceId.substringAfter('.').titleCase(),
                appName = surfaceId.substringBefore('.').titleCase(),
                plural = false,
                isSection = true,
            )
            else -> surfaceId.ifBlank { "this app" }.titleCase().let { SurfaceCopy(it, it, plural = false, isSection = false) }
        }
    }

    /** Label for a package name: known apps first, else the most meaningful segment, title-cased. */
    fun appLabel(packageName: String): String {
        knownApps[packageName]?.let { return it }
        val segment = packageName.split('.').filter { it.isNotBlank() }
            .lastOrNull { it.lowercase(Locale.ROOT) !in genericSegments }
            ?: packageName.substringAfterLast('.').ifBlank { "this app" }
        return segment.titleCase()
    }

    /** "Coaching" -> "Coaching block"; "Coaching block" stays; blank -> null (opened outside a block). */
    fun blockPhrase(scheduleLabel: String): String? {
        val label = scheduleLabel.trim()
        if (label.isEmpty()) return null
        return if (label.endsWith("block", ignoreCase = true)) label else "$label block"
    }

    /** Show "down from N" only when it is actually lower than usual. */
    fun showDownFrom(opensToday: Int, opensBaseline: Int): Boolean = opensBaseline > opensToday

    /** Usual opens per day: the rounded mean of the previous days' counts (0 when there is no history). */
    fun baseline(previousDays: List<Int>): Int =
        if (previousDays.isEmpty()) 0 else previousDays.map { it.coerceAtLeast(0) }.average().roundToInt()

    private fun String.titleCase(): String =
        replace('_', ' ').replace('-', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
}

/**
 * The 5-second breath that gates the two softer exits. "Close" is never gated: in the one sec study the
 * easy exit did most of the work, so it is available immediately.
 */
object BreathGate {
    const val BREATH_SECONDS = 5
    private const val BREATH_MS = BREATH_SECONDS * 1000L

    /** Whole seconds left (rounded up), 0 once the breath is done. */
    fun secondsLeft(elapsedMs: Long): Int {
        val left = BREATH_MS - elapsedMs.coerceAtLeast(0)
        return if (left <= 0) 0 else ((left + 999) / 1000).toInt()
    }

    /** Swap and "just messages" open once the breath is done. */
    fun softExitsEnabled(elapsedMs: Long): Boolean = elapsedMs >= BREATH_MS

    /** Which exits are tappable [elapsedMs] after the sheet appeared. */
    fun exits(elapsedMs: Long): ExitGates =
        softExitsEnabled(elapsedMs).let { ExitGates(close = true, swap = it, messages = it) }
}

data class ExitGates(val close: Boolean, val swap: Boolean, val messages: Boolean)
