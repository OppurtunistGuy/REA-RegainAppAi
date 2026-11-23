package app.steady.detect

enum class EnforcementLevel { HIDE_SECTION, BLOCK_APP, BLOCK_DOMAIN }

/**
 * Tracks, per package, how often the detector anchor (e.g. the bottom tab bar) was seen in the last
 * [windowSize] samples. If the anchor rate drops below [minAnchorRate], the app has probably changed
 * its view ids and section hiding is no longer trustworthy.
 */
class DetectorHealth(private val windowSize: Int = 20, private val minAnchorRate: Double = 0.3) {
    init {
        require(windowSize > 0) { "windowSize must be > 0" }
    }

    private val windows = HashMap<String, ArrayDeque<Boolean>>()

    @Synchronized
    fun record(packageName: String, anchorSeen: Boolean) {
        val w = windows.getOrPut(packageName) { ArrayDeque(windowSize) }
        w.addLast(anchorSeen)
        while (w.size > windowSize) w.removeFirst()
    }

    /** True until [windowSize] samples exist for the package. */
    @Synchronized
    fun isHealthy(packageName: String): Boolean {
        val w = windows[packageName] ?: return true
        if (w.size < windowSize) return true
        return w.count { it }.toDouble() / w.size >= minAnchorRate
    }
}
