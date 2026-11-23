package app.steady.detect

/** Result of one detection pass. [surfaceId] is null when no short-video surface is on screen. */
data class Detection(val surfaceId: String?, val configVersion: Int, val matchedBy: Matcher?)

class SurfaceDetector(private val config: DetectorConfig) {

    private val byPackage: Map<String, List<Detector>> = config.detectors.groupBy { it.packageName }

    /**
     * Depth-first, pre-order. Detectors are tried in config order; within one detector the first
     * node (in DFS order) that matches any matcher wins, with matchers checked in config order on
     * that node. At most [MAX_NODES] nodes are visited per call, shared across all detectors.
     */
    fun detect(packageName: String, root: ViewNode): Detection {
        val detectors = byPackage[packageName] ?: return miss()
        val budget = intArrayOf(MAX_NODES)
        for (detector in detectors) {
            val matchers = detector.anyOf.filterNot { it.isEmpty }
            if (matchers.isEmpty()) continue
            var hit: Matcher? = null
            walk(root, budget) { node ->
                hit = matchers.firstOrNull { it.matches(node) }
                hit != null
            }
            hit?.let { return Detection(detector.surfaceId, config.version, it) }
            if (budget[0] <= 0) break
        }
        return miss()
    }

    /** True if any detector for [packageName] has an anchor and that anchor is on screen. */
    fun anchorSeen(packageName: String, root: ViewNode): Boolean {
        val anchors = byPackage[packageName].orEmpty().mapNotNull { it.anchor }.filterNot { it.isEmpty }
        if (anchors.isEmpty()) return false
        var seen = false
        walk(root, intArrayOf(MAX_NODES)) { node ->
            seen = anchors.any { it.matches(node) }
            seen
        }
        return seen
    }

    private fun miss() = Detection(null, config.version, null)

    /** Iterative DFS (no recursion, so deep trees cannot overflow the stack). Stops when [visit] returns true. */
    private inline fun walk(root: ViewNode, budget: IntArray, visit: (ViewNode) -> Boolean) {
        val stack = ArrayDeque<ViewNode>()
        stack.addLast(root)
        while (stack.isNotEmpty() && budget[0] > 0) {
            val node = stack.removeLast()
            budget[0]--
            if (visit(node)) return
            val kids = node.children
            for (i in kids.indices.reversed()) stack.addLast(kids[i])
        }
    }

    companion object {
        const val MAX_NODES = 4000
    }
}
