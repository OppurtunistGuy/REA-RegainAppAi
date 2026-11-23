package app.steady.detect

import app.steady.rules.SurfaceMode

object FallbackLadder {
    /**
     * Rules-free form of the ladder. [blockWholeApp] is true for `SurfaceMode.BLOCK_APP`.
     * - accessibility off -> BLOCK_DOMAIN (VPN/DNS fallback)
     * - BLOCK_APP mode -> BLOCK_APP
     * - HIDE_SECTION mode: healthy -> HIDE_SECTION, unhealthy -> BLOCK_APP
     *
     */
    /** Contract signature (docs/CONTRACTS.md, Phase 3). */
    fun level(mode: SurfaceMode, healthy: Boolean, accessibilityOn: Boolean): EnforcementLevel =
        level(blockWholeApp = mode == SurfaceMode.BLOCK_APP, healthy = healthy, accessibilityOn = accessibilityOn)

    internal fun level(blockWholeApp: Boolean, healthy: Boolean, accessibilityOn: Boolean): EnforcementLevel =
        when {
            !accessibilityOn -> EnforcementLevel.BLOCK_DOMAIN
            blockWholeApp -> EnforcementLevel.BLOCK_APP
            healthy -> EnforcementLevel.HIDE_SECTION
            else -> EnforcementLevel.BLOCK_APP
        }
}
