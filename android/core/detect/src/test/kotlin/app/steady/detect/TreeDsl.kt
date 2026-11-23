package app.steady.detect

import java.io.File

/** Mutable fake node used only in tests. */
class FakeNode(
    override val viewId: String? = null,
    override val className: String? = null,
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val isSelected: Boolean = false,
) : ViewNode {
    override val children: MutableList<ViewNode> = mutableListOf()

    fun node(
        id: String? = null,
        cls: String? = null,
        text: String? = null,
        desc: String? = null,
        selected: Boolean = false,
        block: FakeNode.() -> Unit = {},
    ): FakeNode = FakeNode(id, cls, text, desc, selected).apply(block).also { children += it }
}

/** Build a fake tree: `tree { node(id = "x:id/a") { node(desc = "Reels", selected = true) } }`. */
fun tree(id: String? = null, cls: String? = "android.widget.FrameLayout", block: FakeNode.() -> Unit = {}): FakeNode =
    FakeNode(viewId = id, className = cls).apply(block)

/** Seed config loaded from shared/detectors/detectors.v1.json (path injected by Gradle). */
val seedConfig: DetectorConfig by lazy {
    val path = System.getProperty("steady.detectors.seed")
        ?: error("system property steady.detectors.seed not set; run tests via Gradle")
    DetectorCodec.decode(File(path).readText())
}
