package app.steady.detect

object FallbackLadder {
    /**
     * Rules-free form of the ladder. [blockWholeApp] is true for `SurfaceMode.BLOCK_APP`.
     * - accessibility off -> BLOCK_DOMAIN (VPN/DNS fallback)
     * - BLOCK_APP mode -> BLOCK_APP
     * - HIDE_SECTION mode: healthy -> HIDE_SECTION, unhealthy -> BLOCK_APP
     *
     * The contract overload `level(mode: SurfaceMode, ...)` is in FallbackLadderRules.kt (disabled
     * until core:rules is merged).
     */
    internal fun level(blockWholeApp: Boolean, healthy: Boolean, accessibilityOn: Boolean): EnforcementLevel =
        when {
            !accessibilityOn -> EnforcementLevel.BLOCK_DOMAIN
            blockWholeApp -> EnforcementLevel.BLOCK_APP
            healthy -> EnforcementLevel.HIDE_SECTION
            else -> EnforcementLevel.BLOCK_APP
        }
}
