package app.steady.rules

import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class UnlockStep { WAITING, NEEDS_REASON, READY, NEEDS_KEYHOLDER }

/**
 * The friction flow before an unlock:
 * 1. WAITING until `policy.waitSeconds` have passed since [step]'s `waitStartedAt`.
 * 2. NEEDS_REASON if `requireReason` and the reason has fewer than [MIN_REASON_CHARS] non-space characters.
 * 3. NEEDS_KEYHOLDER if a key holder exists and `notifyKeyHolder` (approval comes via backend).
 * 4. Otherwise READY (the user may [selfGrant]).
 */
class UnlockFlow(private val policy: UnlockPolicy, private val hasKeyHolder: Boolean) {

    fun step(waitStartedAt: Instant, now: Instant, reason: String): UnlockStep = when {
        secondsLeft(waitStartedAt, now) > 0 -> UnlockStep.WAITING
        policy.requireReason && !isReasonValid(reason) -> UnlockStep.NEEDS_REASON
        hasKeyHolder && policy.notifyKeyHolder -> UnlockStep.NEEDS_KEYHOLDER
        else -> UnlockStep.READY
    }

    /** Whole seconds left in the wait (rounded up), never negative. */
    fun secondsLeft(waitStartedAt: Instant, now: Instant): Int {
        val remainingMs = policy.waitSeconds * 1000L - Duration.between(waitStartedAt, now).toMillis()
        if (remainingMs <= 0) return 0
        return ((remainingMs + 999) / 1000).toInt()
    }

    fun selfGrant(surfaceId: String?, now: Instant, minutes: Int = policy.approvalMinutes): UnlockGrant {
        require(minutes > 0) { "minutes must be positive" }
        return UnlockGrant(
            id = UUID.randomUUID().toString(),
            surfaceId = surfaceId,
            from = now,
            until = now.plusSeconds(minutes * 60L),
            approvedBy = null,
        )
    }

    companion object {
        const val MIN_REASON_CHARS = 8

        fun isReasonValid(reason: String): Boolean = reason.count { !it.isWhitespace() } >= MIN_REASON_CHARS
    }
}
