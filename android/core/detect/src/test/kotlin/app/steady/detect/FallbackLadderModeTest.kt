package app.steady.detect

import app.steady.rules.SurfaceMode
import kotlin.test.Test
import kotlin.test.assertEquals

class FallbackLadderModeTest {
    @Test fun hideSectionHealthy() = assertEquals(EnforcementLevel.HIDE_SECTION, FallbackLadder.level(SurfaceMode.HIDE_SECTION, healthy = true, accessibilityOn = true))
    @Test fun hideSectionUnhealthy() = assertEquals(EnforcementLevel.BLOCK_APP, FallbackLadder.level(SurfaceMode.HIDE_SECTION, healthy = false, accessibilityOn = true))
    @Test fun hideSectionA11yOff() = assertEquals(EnforcementLevel.BLOCK_DOMAIN, FallbackLadder.level(SurfaceMode.HIDE_SECTION, healthy = true, accessibilityOn = false))
    @Test fun blockAppA11yOn() = assertEquals(EnforcementLevel.BLOCK_APP, FallbackLadder.level(SurfaceMode.BLOCK_APP, healthy = true, accessibilityOn = true))
    @Test fun blockAppA11yOff() = assertEquals(EnforcementLevel.BLOCK_DOMAIN, FallbackLadder.level(SurfaceMode.BLOCK_APP, healthy = false, accessibilityOn = false))
}
