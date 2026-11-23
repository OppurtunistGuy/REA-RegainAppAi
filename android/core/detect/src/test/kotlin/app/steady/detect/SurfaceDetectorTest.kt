package app.steady.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val IG = "com.instagram.android"
private const val YT = "com.google.android.youtube"
private const val SC = "com.snapchat.android"
private const val FB = "com.facebook.katana"

class SurfaceDetectorTest {
    private val detector = SurfaceDetector(seedConfig)

    // ---- realistic fake trees -------------------------------------------------------------

    private fun instagramHome() = tree("$IG:id/root") {
        node("$IG:id/layout_container_main") {
            node("$IG:id/feed_recycler", "androidx.recyclerview.widget.RecyclerView") {
                node("$IG:id/row_feed_photo_profile_name", text = "friend_account")
                node("$IG:id/row_feed_button_like", desc = "Like")
                node("$IG:id/row_feed_textview_comments", text = "Watch my new reels on my profile")
            }
        }
        node("$IG:id/tab_bar") {
            node("$IG:id/feed_tab", desc = "Home", selected = true)
            node("$IG:id/search_tab", desc = "Search and explore")
            node("$IG:id/clips_tab", desc = "Reels")
            node("$IG:id/profile_tab", desc = "Profile")
        }
    }

    private fun instagramReels() = tree("$IG:id/root") {
        node("$IG:id/layout_container_main") {
            node("$IG:id/clips_viewer_view_pager", "androidx.viewpager2.widget.ViewPager2") {
                node("$IG:id/clips_video_container")
            }
        }
        node("$IG:id/tab_bar") {
            node("$IG:id/feed_tab", desc = "Home")
            node("$IG:id/clips_tab", desc = "Reels", selected = true)
        }
    }

    private fun youtubeHome() = tree("$YT:id/root") {
        node("$YT:id/results") {
            node("$YT:id/video_title", text = "Lecture 4: Linear algebra")
            node(desc = "Shorts shelf") // not selected -> must not fire
        }
        node("$YT:id/pivot_bar") {
            node(desc = "Home", selected = true)
            node(desc = "Shorts")
            node(desc = "Subscriptions")
        }
    }

    private fun youtubeShorts() = tree("$YT:id/root") {
        node("$YT:id/reel_player_page_container") {
            node("$YT:id/reel_recycler")
        }
        node("$YT:id/pivot_bar") {
            node(desc = "Shorts", selected = true)
        }
    }

    private fun snapchat(spotlightSelected: Boolean) = tree("$SC:id/root") {
        node("$SC:id/camera_preview")
        node("$SC:id/ngs_navigation_bar") {
            node(desc = "Map")
            node(desc = "Camera", selected = !spotlightSelected)
            node(desc = "Spotlight", selected = spotlightSelected)
        }
    }

    // ---- seed detectors ------------------------------------------------------------------

    @Test fun `seed file loads with ignored unknown keys`() {
        assertEquals(1, seedConfig.version)
        assertEquals(
            listOf("instagram.reels", "youtube.shorts", "snapchat.spotlight", "facebook.reels"),
            seedConfig.detectors.map { it.surfaceId },
        )
    }

    @Test fun `instagram reels fires on clips viewer pager`() {
        val d = detector.detect(IG, instagramReels())
        assertEquals("instagram.reels", d.surfaceId)
        assertEquals(1, d.configVersion)
        assertEquals(":id/clips_viewer_view_pager", d.matchedBy?.viewIdEndsWith)
    }

    @Test fun `instagram reels fires on selected clips tab alone`() {
        val t = tree { node("$IG:id/tab_bar") { node("$IG:id/clips_tab", selected = true) } }
        val d = detector.detect(IG, t)
        assertEquals("instagram.reels", d.surfaceId)
        assertEquals(":id/clips_tab", d.matchedBy?.viewIdEndsWith)
    }

    @Test fun `instagram home feed does not fire`() {
        val d = detector.detect(IG, instagramHome())
        assertNull(d.surfaceId)
        assertNull(d.matchedBy)
        assertEquals(1, d.configVersion)
    }

    @Test fun `youtube shorts fires and home does not`() {
        val d = detector.detect(YT, youtubeShorts())
        assertEquals("youtube.shorts", d.surfaceId)
        // DFS order: reel_player_page_container is visited first
        assertEquals(":id/reel_player_page_container", d.matchedBy?.viewIdEndsWith)
        assertNull(detector.detect(YT, youtubeHome()).surfaceId)
    }

    @Test fun `youtube shorts fires on selected pivot tab only`() {
        val t = tree { node("$YT:id/pivot_bar") { node(desc = "Shorts", selected = true) } }
        assertEquals("youtube.shorts", detector.detect(YT, t).surfaceId)
    }

    @Test fun `snapchat spotlight fires only when selected`() {
        assertEquals("snapchat.spotlight", detector.detect(SC, snapchat(true)).surfaceId)
        assertNull(detector.detect(SC, snapchat(false)).surfaceId)
    }

    @Test fun `facebook reels fires on viewer or selected tab, not home`() {
        val viewer = tree { node("$FB:id/reels_viewer") }
        val tab = tree { node(desc = "Reels, tab 2 of 6", selected = true) }
        val home = tree {
            node(desc = "Home, tab 1 of 6", selected = true)
            node(desc = "Reels, tab 2 of 6")
        }
        assertEquals("facebook.reels", detector.detect(FB, viewer).surfaceId)
        assertEquals("facebook.reels", detector.detect(FB, tab).surfaceId)
        assertNull(detector.detect(FB, home).surfaceId)
    }

    @Test fun `wrong package returns null`() {
        val d = detector.detect("com.example.notes", instagramReels())
        assertNull(d.surfaceId)
        assertNull(d.matchedBy)
        assertEquals(1, d.configVersion)
        // instagram tree checked as youtube: different ids, no hit
        assertNull(detector.detect(YT, instagramReels()).surfaceId)
    }

    // ---- matcher semantics ---------------------------------------------------------------

    @Test fun `selected matcher semantics`() {
        val mTrue = Matcher(descContains = "Reels", selected = true)
        val mFalse = Matcher(descContains = "Reels", selected = false)
        val mAny = Matcher(descContains = "Reels")
        val sel = FakeNode(contentDescription = "Reels", isSelected = true)
        val unsel = FakeNode(contentDescription = "Reels", isSelected = false)
        assertTrue(mTrue.matches(sel)); assertFalse(mTrue.matches(unsel))
        assertTrue(mFalse.matches(unsel)); assertFalse(mFalse.matches(sel))
        assertTrue(mAny.matches(sel)); assertTrue(mAny.matches(unsel))
    }

    @Test fun `multi-field matcher requires all fields on the same node`() {
        val cfg = DetectorConfig(
            3, listOf(Detector("x.reels", "x", listOf(Matcher(viewIdEndsWith = ":id/tab", selected = true)))),
        )
        val split = tree {
            node("x:id/tab", selected = false)
            node("x:id/other", selected = true)
        }
        val same = tree { node("x:id/tab", selected = true) }
        val sd = SurfaceDetector(cfg)
        assertNull(sd.detect("x", split).surfaceId)
        val hit = sd.detect("x", same)
        assertEquals("x.reels", hit.surfaceId)
        assertEquals(3, hit.configVersion)
    }

    @Test fun `every matcher field is checked`() {
        val n = FakeNode("p:id/a", "android.widget.TextView", "Shorts", "Go to Shorts", true)
        assertTrue(Matcher(viewIdEquals = "p:id/a").matches(n))
        assertFalse(Matcher(viewIdEquals = "p:id/ab").matches(n))
        assertTrue(Matcher(textEquals = "Shorts").matches(n))
        assertFalse(Matcher(textEquals = "shorts").matches(n))
        assertTrue(Matcher(classEquals = "android.widget.TextView").matches(n))
        assertFalse(Matcher(classEquals = "android.view.View").matches(n))
        assertTrue(Matcher(descContains = "shorts").matches(n)) // case-insensitive
        assertFalse(Matcher(descContains = "Reels").matches(n))
        assertTrue(
            Matcher("p:id/a", ":id/a", "Shorts", "Shorts", "android.widget.TextView", true).matches(n),
        )
        assertFalse(Matcher(viewIdEquals = "p:id/a", textEquals = "nope").matches(n))
    }

    @Test fun `null fields on nodes never match and never throw`() {
        val blank = FakeNode()
        assertFalse(Matcher(viewIdEndsWith = ":id/x").matches(blank))
        assertFalse(Matcher(viewIdEquals = "a").matches(blank))
        assertFalse(Matcher(textEquals = "a").matches(blank))
        assertFalse(Matcher(descContains = "a").matches(blank))
        assertFalse(Matcher(classEquals = "a").matches(blank))
        assertNull(detector.detect(IG, tree(id = null, cls = null) { node(); node { node() } }).surfaceId)
    }

    @Test fun `empty matcher matches nothing`() {
        assertFalse(Matcher().matches(FakeNode("x:id/a")))
        val cfg = DetectorConfig(1, listOf(Detector("x.reels", "x", listOf(Matcher()), anchor = Matcher())))
        val sd = SurfaceDetector(cfg)
        assertNull(sd.detect("x", tree { node("x:id/a") }).surfaceId)
        assertFalse(sd.anchorSeen("x", tree { node("x:id/a") }))
    }

    // ---- ordering ------------------------------------------------------------------------

    @Test fun `first hit wins in detector order then matcher order`() {
        val cfg = DetectorConfig(
            2,
            listOf(
                Detector("x.first", "x", listOf(Matcher(viewIdEndsWith = ":id/b"), Matcher(viewIdEndsWith = ":id/a"))),
                Detector("x.second", "x", listOf(Matcher(viewIdEndsWith = ":id/a"))),
            ),
        )
        val sd = SurfaceDetector(cfg)
        // :id/a is before :id/b in DFS, but detector order wins: x.first matches :id/a first in DFS.
        val t = tree { node("x:id/a"); node("x:id/b") }
        val d = sd.detect("x", t)
        assertEquals("x.first", d.surfaceId)
        assertEquals(":id/a", d.matchedBy?.viewIdEndsWith)
        // On a single node matching both matchers, the earlier matcher is reported.
        val both = tree { node("x:id/a") }
        val m = Matcher(viewIdEquals = "x:id/a")
        val sd2 = SurfaceDetector(DetectorConfig(1, listOf(Detector("y", "x", listOf(m, Matcher(viewIdEndsWith = ":id/a"))))))
        assertEquals(m, sd2.detect("x", both).matchedBy)
        // Second detector fires when the first misses.
        val missFirst = DetectorConfig(
            1,
            listOf(cfg.detectors[0].copy(anyOf = listOf(Matcher(viewIdEndsWith = ":id/zzz"))), cfg.detectors[1]),
        )
        assertEquals("x.second", SurfaceDetector(missFirst).detect("x", tree { node("x:id/a") }).surfaceId)
    }

    @Test fun `depth first pre-order`() {
        val cfg = DetectorConfig(1, listOf(Detector("x", "x", listOf(Matcher(descContains = "hit")))))
        val t = tree {
            node { node { node(id = "deep", desc = "hit deep") } }
            node(id = "shallow", desc = "hit shallow")
        }
        val d = SurfaceDetector(cfg).detect("x", t)
        assertEquals("x", d.surfaceId)
        // Both nodes match; DFS reaches the deep node of the first branch before its sibling.
        val log = mutableListOf<String?>()
        SurfaceDetector(cfg).detect("x", recorder(t, log))
        assertEquals("deep", log.last())
    }

    private fun recorder(n: ViewNode, log: MutableList<String?>): ViewNode = object : ViewNode by n {
        override val contentDescription: String? get() = n.contentDescription.also { log += n.viewId }
        override val children: List<ViewNode> get() = n.children.map { recorder(it, log) }
    }

    // ---- node cap ------------------------------------------------------------------------

    @Test fun `deep 10k node tree is capped and returns quickly with null`() {
        // A chain 10_000 deep whose only matching node is at the bottom.
        val root = FakeNode("$IG:id/root")
        var cur = root
        repeat(9_998) { cur = cur.node("$IG:id/n$it") }
        cur.node("$IG:id/clips_viewer_view_pager")
        val start = System.nanoTime()
        val d = detector.detect(IG, root)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertNull(d.surfaceId)
        assertTrue(ms < 500, "took ${ms}ms")
        assertFalse(detector.anchorSeen(IG, root))
    }

    @Test fun `node cap counts visited nodes exactly`() {
        val cfg = DetectorConfig(1, listOf(Detector("x", "x", listOf(Matcher(viewIdEquals = "hit")))))
        val sd = SurfaceDetector(cfg)
        fun wide(hitIndex: Int) = tree(id = "root") {
            // root counts as node #1, so child i is node #(i + 2)
            for (i in 0 until 5000) node(if (i == hitIndex) "hit" else "c$i")
        }
        assertEquals("x", sd.detect("x", wide(SurfaceDetector.MAX_NODES - 2)).surfaceId)
        assertNull(sd.detect("x", wide(SurfaceDetector.MAX_NODES - 1)).surfaceId)
    }

    @Test fun `node cap is shared across detectors of one package`() {
        val cfg = DetectorConfig(
            1,
            listOf(
                Detector("x.a", "x", listOf(Matcher(viewIdEquals = "never"))),
                Detector("x.b", "x", listOf(Matcher(viewIdEquals = "c0"))),
            ),
        )
        val t = tree { for (i in 0 until 5000) node("c$i") }
        // first detector exhausts the budget, so second never runs
        assertNull(SurfaceDetector(cfg).detect("x", t).surfaceId)
    }

    // ---- anchor --------------------------------------------------------------------------

    @Test fun `anchorSeen finds anchors per package`() {
        assertTrue(detector.anchorSeen(IG, instagramHome()))
        assertTrue(detector.anchorSeen(IG, instagramReels()))
        assertTrue(detector.anchorSeen(YT, youtubeHome()))
        assertTrue(detector.anchorSeen(SC, snapchat(false)))
        assertFalse(detector.anchorSeen(IG, tree { node("$IG:id/clips_viewer_view_pager") }))
        assertFalse(detector.anchorSeen(YT, instagramHome()))
        // facebook seed has no anchor
        assertFalse(detector.anchorSeen(FB, tree { node("$FB:id/reels_viewer") }))
        assertFalse(detector.anchorSeen("com.unknown", instagramHome()))
    }

    @Test fun `detection does not require anchor`() {
        assertNotNull(detector.detect(IG, tree { node("$IG:id/clips_viewer_view_pager") }).surfaceId)
    }

    // ---- codec ---------------------------------------------------------------------------

    @Test fun `codec skips unknown keys and applies defaults`() {
        val cfg = DetectorCodec.decode(
            """{"version":7,"_note":"x","extra":{"a":1},"detectors":[
               {"surfaceId":"s","packageName":"p","anyOf":[{"textEquals":"t","_why":"y"}],"future":true}]}""",
        )
        assertEquals(7, cfg.version)
        assertNull(cfg.detectors[0].anchor)
        assertEquals(Matcher(textEquals = "t"), cfg.detectors[0].anyOf[0])
    }
}
