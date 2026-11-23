package app.steady.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetectorHealthTest {
    @Test fun `healthy until window is full`() {
        val h = DetectorHealth(windowSize = 5, minAnchorRate = 0.5)
        assertTrue(h.isHealthy("p"))
        repeat(4) { h.record("p", false); assertTrue(h.isHealthy("p")) }
        h.record("p", false)
        assertFalse(h.isHealthy("p"))
    }

    @Test fun `rate threshold is inclusive`() {
        val h = DetectorHealth(windowSize = 10, minAnchorRate = 0.3)
        repeat(3) { h.record("p", true) }
        repeat(7) { h.record("p", false) }
        assertTrue(h.isHealthy("p")) // exactly 0.3
        h.record("p", false) // window slides: drops a true -> 0.2
        assertFalse(h.isHealthy("p"))
    }

    @Test fun `window rolls and recovers`() {
        val h = DetectorHealth(windowSize = 4, minAnchorRate = 0.5)
        repeat(4) { h.record("p", false) }
        assertFalse(h.isHealthy("p"))
        h.record("p", true)
        assertFalse(h.isHealthy("p")) // 1/4
        h.record("p", true)
        assertTrue(h.isHealthy("p")) // 2/4
    }

    @Test fun `packages are tracked independently`() {
        val h = DetectorHealth()
        repeat(20) { h.record("a", false); h.record("b", true) }
        assertFalse(h.isHealthy("a"))
        assertTrue(h.isHealthy("b"))
        assertTrue(h.isHealthy("never.seen"))
    }

    @Test fun `default window is 20 at 30 percent`() {
        val h = DetectorHealth()
        repeat(6) { h.record("p", true) }
        repeat(13) { h.record("p", false) }
        assertTrue(h.isHealthy("p")) // 19 samples: not full yet
        h.record("p", false)
        assertTrue(h.isHealthy("p")) // 6/20 = 0.3
        h.record("p", false)
        assertFalse(h.isHealthy("p")) // 5/20
    }

    @Test fun `window size must be positive`() {
        assertFailsWith<IllegalArgumentException> { DetectorHealth(windowSize = 0) }
    }
}

class FallbackLadderTest {
    private val hide = false
    private val block = true

    @Test fun `hide section, healthy, a11y on`() =
        assertEquals(EnforcementLevel.HIDE_SECTION, FallbackLadder.level(hide, healthy = true, accessibilityOn = true))

    @Test fun `hide section, unhealthy, a11y on`() =
        assertEquals(EnforcementLevel.BLOCK_APP, FallbackLadder.level(hide, healthy = false, accessibilityOn = true))

    @Test fun `hide section, a11y off`() {
        assertEquals(EnforcementLevel.BLOCK_DOMAIN, FallbackLadder.level(hide, healthy = true, accessibilityOn = false))
        assertEquals(EnforcementLevel.BLOCK_DOMAIN, FallbackLadder.level(hide, healthy = false, accessibilityOn = false))
    }

    @Test fun `block app, a11y on`() {
        assertEquals(EnforcementLevel.BLOCK_APP, FallbackLadder.level(block, healthy = true, accessibilityOn = true))
        assertEquals(EnforcementLevel.BLOCK_APP, FallbackLadder.level(block, healthy = false, accessibilityOn = true))
    }

    @Test fun `block app, a11y off`() {
        assertEquals(EnforcementLevel.BLOCK_DOMAIN, FallbackLadder.level(block, healthy = true, accessibilityOn = false))
        assertEquals(EnforcementLevel.BLOCK_DOMAIN, FallbackLadder.level(block, healthy = false, accessibilityOn = false))
    }
}
