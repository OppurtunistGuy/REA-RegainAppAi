package app.steady.nav

import kotlinx.serialization.Serializable

/** Type-safe navigation-compose routes. One per screen in CONTRACTS "Phase 5 — app shell slots". */
@Serializable sealed interface Route

@Serializable data object Setup : Route
@Serializable data object Today : Route

/** Intercept sheet shown in-app (debug entry from Today; Phase 6 also shows it as an overlay). */
@Serializable data class Intercept(val surfaceId: String = "instagram.reels") : Route

/** 60-second flashcard swap. */
@Serializable data object Swap : Route

/** Focus block. [scheduleId] null = a manual block started from Today. */
@Serializable data class Focus(val scheduleId: String? = null) : Route

@Serializable data object Recap : Route
@Serializable data object Review : Route
@Serializable data object Insights : Route
@Serializable data object Partner : Route
@Serializable data object Paywall : Route
@Serializable data object Settings : Route
@Serializable data object Disclosure : Route

/**
 * What screens use to move around. Screens never touch NavController directly, so later phases can
 * navigate anywhere without editing `nav/`.
 */
interface Navigator {
    fun go(route: Route)
    fun back()
    /** Clears the back stack and shows [route] (e.g. Setup -> Today). */
    fun resetTo(route: Route)
}
