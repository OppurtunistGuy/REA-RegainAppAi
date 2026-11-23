package app.steady.enforcement

import app.steady.detect.Detector
import app.steady.detect.DetectorConfig
import app.steady.detect.Matcher
import app.steady.detect.SurfaceDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSource(
    override val viewId: String? = null,
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val isSelected: Boolean = false,
    override val className: String? = null,
    val kids: List<FakeSource?> = emptyList(),
) : NodeSource {
    var childCalls = 0
    var recycled = 0
    override val childCount: Int get() = kids.size
    override fun child(index: Int): NodeSource? {
        childCalls++
        return kids[index]
    }
    override fun recycle() {
        recycled++
    }
}

class NodeTreeTest {

    @Test fun mapsPropertiesAndChildrenLazily() {
        val leaf = FakeSource(viewId = "a:id/leaf", text = "hi", contentDescription = "Reels", isSelected = true, className = "android.widget.TextView")
        val root = FakeSource(viewId = "a:id/root", kids = listOf(leaf, null))
        val tree = NodeTree(root)
        assertEquals("a:id/root", tree.root.viewId)
        assertEquals(0, root.childCalls)
        assertEquals(1, tree.size)
        val kids = tree.root.children
        assertEquals(1, kids.size) // null child skipped
        assertEquals(2, root.childCalls)
        val n = kids.single()
        assertEquals("a:id/leaf", n.viewId)
        assertEquals("hi", n.text)
        assertEquals("Reels", n.contentDescription)
        assertTrue(n.isSelected)
        assertEquals("android.widget.TextView", n.className)
        // Cached: a second walk does not fetch again.
        tree.root.children
        assertEquals(2, root.childCalls)
    }

    @Test fun closeRecyclesEverythingOpenedOnceAndStopsLoading() {
        val deep = FakeSource()
        val mid = FakeSource(kids = listOf(deep))
        val root = FakeSource(kids = listOf(mid))
        val tree = NodeTree(root)
        val midNode = tree.root.children.single()
        tree.close()
        tree.close()
        assertEquals(1, root.recycled)
        assertEquals(1, mid.recycled)
        assertEquals(0, deep.recycled) // never opened
        assertEquals(emptyList<Any>(), midNode.children)
        assertEquals(0, mid.childCalls)
    }

    @Test fun respectsNodeCap() {
        val root = FakeSource(kids = List(5000) { FakeSource() })
        val tree = NodeTree(root)
        assertEquals(SurfaceDetector.MAX_NODES - 1, tree.root.children.size)
        assertEquals(SurfaceDetector.MAX_NODES, tree.size)

        val small = NodeTree(FakeSource(kids = listOf(FakeSource(kids = listOf(FakeSource())), FakeSource())), maxNodes = 2)
        val first = small.root.children
        assertEquals(1, first.size)
        assertEquals(emptyList<Any>(), first.single().children)
    }

    @Test fun worksWithSurfaceDetector() {
        val cfg = DetectorConfig(
            1,
            listOf(
                Detector(
                    "instagram.reels", "com.instagram.android",
                    anyOf = listOf(Matcher(descContains = "Reels", selected = true)),
                    anchor = Matcher(viewIdEndsWith = ":id/tab_bar"),
                ),
            ),
        )
        val detector = SurfaceDetector(cfg)
        fun screen(reelsSelected: Boolean) = FakeSource(
            kids = listOf(
                FakeSource(viewId = "com.instagram.android:id/feed"),
                FakeSource(
                    viewId = "com.instagram.android:id/tab_bar",
                    kids = listOf(FakeSource(contentDescription = "Home"), FakeSource(contentDescription = "Reels", isSelected = reelsSelected)),
                ),
            ),
        )
        NodeTree(screen(true)).use { t ->
            assertEquals("instagram.reels", detector.detect("com.instagram.android", t.root).surfaceId)
            assertTrue(detector.anchorSeen("com.instagram.android", t.root))
        }
        NodeTree(screen(false)).use { t ->
            assertEquals(null, detector.detect("com.instagram.android", t.root).surfaceId)
            assertTrue(detector.anchorSeen("com.instagram.android", t.root))
        }
        NodeTree(FakeSource()).use { t -> assertFalse(detector.anchorSeen("com.instagram.android", t.root)) }
    }
}
