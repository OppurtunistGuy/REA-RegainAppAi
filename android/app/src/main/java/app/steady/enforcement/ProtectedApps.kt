package app.steady.enforcement

/**
 * Packages an `app:<pkg>` block may never target, whatever the plan says (Setup, sync or the
 * extension): the user must always be able to reach Settings (to turn Steady off), install or update
 * apps, call someone, get emergency alerts, and open Steady itself. Enforced in PlanBuilder (Setup),
 * [observedPackages] and [EnforcementDecider].
 */
object ProtectedApps {
    const val STEADY = "app.steady"

    private val exact = setOf(
        STEADY,
        // Settings
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        // Package installers / app stores
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.vending",
        // Phone, dialer, contacts, SMS (emergency contact paths)
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.android.contacts",
        "com.google.android.contacts",
        "com.android.mms",
        "com.google.android.apps.messaging",
        // Emergency
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.android.cellbroadcastreceiver",
        "com.google.android.cellbroadcastreceiver",
        "com.samsung.android.emergency",
        // System UI
        "com.android.systemui",
    )

    /** Prefixes for system variants (e.g. "com.android.settings.intelligence"). */
    private val prefixes = listOf("com.android.settings.", "com.android.emergency.", "com.android.cellbroadcast")

    fun isProtected(packageName: String): Boolean {
        val pkg = packageName.trim()
        return pkg in exact || prefixes.any { pkg.startsWith(it) }
    }

    /** True for an `app:<pkg>` surface id that targets a protected package. */
    fun isProtectedSurface(surfaceId: String): Boolean =
        surfaceId.startsWith("app:") && isProtected(surfaceId.removePrefix("app:"))
}
