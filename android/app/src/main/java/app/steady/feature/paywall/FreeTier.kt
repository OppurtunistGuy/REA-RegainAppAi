package app.steady.feature.paywall

import app.steady.data.Entitlement
import app.steady.data.Features
import app.steady.data.Tier

/**
 * Free-tier rules (docs/CONTRACTS.md "Free tier limits"), read by Setup, Paywall and Settings.
 * The single source of the free limits. Strict mode itself is never limited.
 */
object FreeTier {
    /** CONTRACTS: free = 2 schedules. */
    const val SCHEDULE_LIMIT = 2
    const val KEY_HOLDER_LIMIT = 1

    /** What every user gets, Plus or not. */
    val freeIncludes: List<String> = listOf("strict_mode", "two_schedules", "reels_shorts_hiding", "breath_and_swap", "one_key_holder")

    /** Feature flags Plus adds (match backend `entitlements.ts`). */
    val plusFeatures: Set<String> = setOf(
        Features.UNLIMITED_SCHEDULES, Features.DESKTOP_SYNC, Features.NOTES_TO_CARDS, Features.TASK_BREAKDOWN,
    )

    fun isPlus(e: Entitlement): Boolean = e.tier == Tier.PLUS

    fun has(e: Entitlement, feature: String): Boolean = feature in e.features

    /** null = unlimited. */
    fun scheduleLimit(e: Entitlement): Int? = if (has(e, Features.UNLIMITED_SCHEDULES)) null else SCHEDULE_LIMIT

    fun canAddSchedule(e: Entitlement, currentCount: Int): Boolean =
        scheduleLimit(e)?.let { currentCount < it } ?: true

    /** One key holder on every tier for now (Plus does not promise more). */
    fun keyHolderLimit(@Suppress("UNUSED_PARAMETER") e: Entitlement): Int = KEY_HOLDER_LIMIT

    fun canAddKeyHolder(e: Entitlement, currentCount: Int): Boolean = currentCount < keyHolderLimit(e)

    /** Strict mode stays free. Always true; here so callers never gate it by accident. */
    fun strictModeAvailable(@Suppress("UNUSED_PARAMETER") e: Entitlement): Boolean = true

    /** Plus features this entitlement lacks, in a stable order (for upsell copy). */
    fun missingPlusFeatures(e: Entitlement): List<String> = plusFeatures.filterNot { it in e.features }.sorted()
}
