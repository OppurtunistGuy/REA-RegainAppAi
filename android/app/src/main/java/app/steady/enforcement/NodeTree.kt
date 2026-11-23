package app.steady.enforcement

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import app.steady.detect.SurfaceDetector
import app.steady.detect.ViewNode

/** Minimal platform view of one accessibility node, so the adapter can be tested with fakes. */
interface NodeSource {
    val viewId: String?
    val className: String?
    val text: String?
    val contentDescription: String?
    val isSelected: Boolean
    val childCount: Int
    fun child(index: Int): NodeSource?
    fun recycle()
}

/**
 * Lazily maps a [NodeSource] tree to [ViewNode]s for one detection pass.
 * - Children are fetched only when a walker asks for them, then cached (detect + anchorSeen share them).
 * - At most [maxNodes] nodes are ever materialised, matching SurfaceDetector's cap.
 * - Properties are copied when a node is created, so [ViewNode]s stay valid after [close].
 * - [close] recycles every source it opened (the root included); later child lookups return empty.
 * Nothing here is stored or sent anywhere; the tree is dropped after the pass.
 */
class NodeTree(rootSource: NodeSource, private val maxNodes: Int = SurfaceDetector.MAX_NODES) : AutoCloseable {
    private val opened = ArrayList<NodeSource>().apply { add(rootSource) }
    private var remaining = maxNodes - 1
    private var closed = false

    val root: ViewNode = Node(rootSource)

    /** Number of nodes materialised so far (root included). */
    val size: Int get() = maxNodes - remaining

    private inner class Node(source: NodeSource) : ViewNode {
        override val viewId: String? = source.viewId
        override val className: String? = source.className
        override val text: String? = source.text
        override val contentDescription: String? = source.contentDescription
        override val isSelected: Boolean = source.isSelected
        override val children: List<ViewNode> by lazy(LazyThreadSafetyMode.NONE) { load(source) }
    }

    private fun load(source: NodeSource): List<ViewNode> {
        if (closed) return emptyList()
        val count = source.childCount
        if (count <= 0 || remaining <= 0) return emptyList()
        val out = ArrayList<ViewNode>(minOf(count, remaining))
        for (i in 0 until count) {
            if (remaining <= 0) break
            val child = source.child(i) ?: continue
            opened += child
            remaining--
            out += Node(child)
        }
        return out
    }

    override fun close() {
        if (closed) return
        closed = true
        opened.forEach { runCatching { it.recycle() } }
        opened.clear()
    }
}

/** [NodeSource] over a real [AccessibilityNodeInfo]. */
class AccessibilityNodeSource(private val info: AccessibilityNodeInfo) : NodeSource {
    override val viewId: String? get() = info.viewIdResourceName
    override val className: String? get() = info.className?.toString()
    override val text: String? get() = info.text?.toString()
    override val contentDescription: String? get() = info.contentDescription?.toString()
    override val isSelected: Boolean get() = info.isSelected
    override val childCount: Int get() = info.childCount
    override fun child(index: Int): NodeSource? = info.getChild(index)?.let(::AccessibilityNodeSource)

    @Suppress("DEPRECATION")
    override fun recycle() {
        // Deprecated no-op from API 33; before that it returns the node to the system pool.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) info.recycle()
    }
}
