package app.steady.detect

/**
 * Platform-free view of one node in an app's UI tree.
 * Phase 6 maps AccessibilityNodeInfo onto this interface.
 */
interface ViewNode {
    val viewId: String?
    val className: String?
    val text: String?
    val contentDescription: String?
    val isSelected: Boolean
    val children: List<ViewNode>
}
