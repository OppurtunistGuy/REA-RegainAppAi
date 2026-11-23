package app.steady.feature.focus

import app.steady.data.StudyTask
import app.steady.rules.Energy
import app.steady.rules.KeyHolder
import app.steady.rules.Plan
import app.steady.rules.Relation
import app.steady.rules.RulesEngine
import app.steady.rules.Schedule
import app.steady.rules.Surface
import app.steady.rules.SurfaceMode
import app.steady.rules.UnlockFlow
import app.steady.rules.UnlockGrant
import app.steady.rules.UnlockPolicy
import app.steady.rules.UnlockStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class FocusClockTest {
    private val t0 = Instant.parse("2026-11-24T10:00:00Z")

    @Test fun remainingRoundsUp_andNeverNegative() {
        assertEquals(1500, FocusClock.remainingSeconds(t0, t0.plusSeconds(1500)))
        assertEquals(1, FocusClock.remainingSeconds(t0, t0.plusMillis(1)))
        assertEquals(930, FocusClock.remainingSeconds(t0.plusMillis(500), t0.plusSeconds(930)))
        assertEquals(0, FocusClock.remainingSeconds(t0, t0))
        assertEquals(0, FocusClock.remainingSeconds(t0.plusSeconds(5), t0))
    }

    @Test fun fraction() {
        assertEquals(0.62f, FocusClock.fraction(930, 1500), 0.0001f)
        assertEquals(1f, FocusClock.fraction(2000, 1500), 0f)
        assertEquals(0f, FocusClock.fraction(0, 1500), 0f)
        assertEquals(0f, FocusClock.fraction(10, 0), 0f)
    }

    @Test fun clockText_isZeroPaddedMmSs() {
        assertEquals("15:30", FocusClock.clock(930))
        assertEquals("05:03", FocusClock.clock(303))
        assertEquals("00:00", FocusClock.clock(0))
        assertEquals("00:00", FocusClock.clock(-4))
        assertEquals("59:59", FocusClock.clock(3599))
        assertEquals("1:00:00", FocusClock.clock(3600))
        assertEquals("2:44:05", FocusClock.clock(2 * 3600 + 44 * 60 + 5))
    }

    @Test fun durationLabel() {
        assertEquals("25 min", FocusClock.durationLabel(1500))
        assertEquals("15 min", FocusClock.durationLabel(15 * 60 + 20))
        assertEquals("3 h", FocusClock.durationLabel(3 * 3600))
        assertEquals("2 h 30 min", FocusClock.durationLabel(150 * 60))
        assertEquals("16 min", FocusClock.spokenRemaining(930))
        assertEquals("1 min", FocusClock.spokenRemaining(1))
        assertEquals("0 min", FocusClock.spokenRemaining(0))
    }

    @Test fun wallTime() {
        assertEquals("6:00 pm", FocusClock.wallTime(LocalTime.of(18, 0)))
        assertEquals("12:30 am", FocusClock.wallTime(LocalTime.of(0, 30)))
        assertEquals("12:05 pm", FocusClock.wallTime(LocalTime.of(12, 5)))
        assertEquals("9:41 am", FocusClock.wallTime(LocalTime.of(9, 41)))
    }
}

class UnlockSheetReducerTest {
    private val t0 = Instant.parse("2026-11-24T12:00:00Z")
    private val policy = UnlockPolicy(waitSeconds = 30, requireReason = true, notifyKeyHolder = true, approvalMinutes = 15)
    private val withKeyHolder = UnlockSheetReducer(UnlockFlow(policy, hasKeyHolder = true), requireReason = true)
    private val selfOnly = UnlockSheetReducer(UnlockFlow(policy, hasKeyHolder = false), requireReason = true)
    private fun opened(r: UnlockSheetReducer) = r.reduce(UnlockSheetState(), UnlockAction.Open(t0))

    @Test fun open_startsWaitCountdown() {
        val s = opened(selfOnly)
        assertEquals(UnlockPhase.Form, s.phase)
        assertTrue(s.isOpen)
        assertEquals(30, selfOnly.view(s, t0).secondsLeft)
        assertEquals(UnlockStep.WAITING, selfOnly.view(s, t0).step)
        assertEquals(1, selfOnly.view(s, t0.plusMillis(29_001)).secondsLeft)
        assertEquals(0, selfOnly.view(s, t0.plusSeconds(30)).secondsLeft)
        // Opening again while open keeps the original wait.
        assertEquals(s, selfOnly.reduce(s, UnlockAction.Open(t0.plusSeconds(10))))
    }

    @Test fun shortReason_blocksSubmit_withLiveHint() {
        var s = opened(selfOnly)
        assertEquals(ReasonHint.NONE, selfOnly.view(s, t0).reasonHint)
        s = selfOnly.reduce(s, UnlockAction.ReasonChanged("need it"))
        val v = selfOnly.view(s, t0.plusSeconds(31))
        assertEquals(ReasonHint.SHORT, v.reasonHint)
        assertEquals(UnlockStep.NEEDS_REASON, v.step)
        assertFalse(v.canSubmit)
        assertEquals(s, selfOnly.reduce(s, UnlockAction.Submit(t0.plusSeconds(31))))
        // Spaces do not count toward the 8 characters.
        s = selfOnly.reduce(s, UnlockAction.ReasonChanged("a b c d e f g"))
        assertEquals(ReasonHint.SHORT, selfOnly.view(s, t0.plusSeconds(31)).reasonHint)
    }

    @Test fun validReason_duringWait_stillWaits() {
        val s = selfOnly.reduce(opened(selfOnly), UnlockAction.ReasonChanged("Lecture link on YouTube"))
        val v = selfOnly.view(s, t0.plusSeconds(10))
        assertEquals(ReasonHint.OK, v.reasonHint)
        assertEquals(UnlockStep.WAITING, v.step)
        assertFalse(v.canSubmit)
        assertEquals(UnlockPhase.Form, selfOnly.reduce(s, UnlockAction.Submit(t0.plusSeconds(10))).phase)
    }

    @Test fun selfPath_sendsThenGrants() {
        var s = selfOnly.reduce(opened(selfOnly), UnlockAction.ReasonChanged("Lecture link on YouTube"))
        s = selfOnly.reduce(s, UnlockAction.SurfaceChosen("youtube.shorts"))
        val v = selfOnly.view(s, t0.plusSeconds(30))
        assertEquals(UnlockStep.READY, v.step)
        assertTrue(v.canSubmit)
        assertFalse(v.asksKeyHolder)
        s = selfOnly.reduce(s, UnlockAction.Submit(t0.plusSeconds(30)))
        assertEquals(UnlockPhase.Sending, s.phase)
        assertEquals("youtube.shorts", s.surfaceId)
        // Typing while sending is ignored.
        assertEquals(s, selfOnly.reduce(s, UnlockAction.ReasonChanged("x")))
        val until = t0.plusSeconds(30 + 15 * 60)
        s = selfOnly.reduce(s, UnlockAction.Granted(until))
        assertEquals(UnlockPhase.Granted(until), s.phase)
        assertEquals(UnlockPhase.Closed, selfOnly.reduce(s, UnlockAction.Dismiss).phase)
    }

    @Test fun keyHolderPath_pendingSurvivesClose() {
        var s = withKeyHolder.reduce(opened(withKeyHolder), UnlockAction.ReasonChanged("Need the lecture link"))
        assertTrue("labelled for key holder during the wait", withKeyHolder.view(s, t0).asksKeyHolder)
        val v = withKeyHolder.view(s, t0.plusSeconds(30))
        assertEquals(UnlockStep.NEEDS_KEYHOLDER, v.step)
        assertTrue(v.canSubmit)
        s = withKeyHolder.reduce(s, UnlockAction.Submit(t0.plusSeconds(30)))
        s = withKeyHolder.reduce(s, UnlockAction.Requested("req-1"))
        assertEquals(UnlockPhase.Pending("req-1"), s.phase)
        s = withKeyHolder.reduce(s, UnlockAction.Dismiss)
        assertFalse(s.isOpen)
        assertEquals("req-1", s.pendingRequestId)
        s = withKeyHolder.reduce(s, UnlockAction.Open(t0.plusSeconds(300)))
        assertEquals(UnlockPhase.Pending("req-1"), s.phase)
    }

    @Test fun failure_allowsRetry() {
        var s = selfOnly.reduce(opened(selfOnly), UnlockAction.ReasonChanged("Lecture link on YouTube"))
        s = selfOnly.reduce(s, UnlockAction.Submit(t0.plusSeconds(40)))
        s = selfOnly.reduce(s, UnlockAction.Failed)
        assertEquals(UnlockPhase.Failed, s.phase)
        assertEquals(UnlockPhase.Sending, selfOnly.reduce(s, UnlockAction.Submit(t0.plusSeconds(41))).phase)
        // Results that arrive when not sending are ignored.
        assertEquals(s, selfOnly.reduce(s, UnlockAction.Granted(t0)))
        assertEquals(s, selfOnly.reduce(s, UnlockAction.Requested("x")))
    }

    @Test fun reasonOptional_whenPolicySaysSo() {
        val r = UnlockSheetReducer(UnlockFlow(policy.copy(requireReason = false), hasKeyHolder = false), requireReason = false)
        val s = opened(r)
        val v = r.view(s, t0.plusSeconds(30))
        assertEquals(ReasonHint.OPTIONAL, v.reasonHint)
        assertEquals(UnlockStep.READY, v.step)
        assertTrue(v.canSubmit)
    }

    @Test fun reopenAfterDismiss_restartsWaitAndClearsReason() {
        var s = selfOnly.reduce(opened(selfOnly), UnlockAction.ReasonChanged("Lecture link on YouTube"))
        s = selfOnly.reduce(s, UnlockAction.Dismiss)
        s = selfOnly.reduce(s, UnlockAction.Open(t0.plusSeconds(100)))
        assertEquals("", s.reason)
        assertEquals(t0.plusSeconds(100), s.waitStartedAt)
    }

    @Test fun reasonIsCapped() {
        val s = selfOnly.reduce(opened(selfOnly), UnlockAction.ReasonChanged("x".repeat(500)))
        assertEquals(UnlockSheetReducer.MAX_REASON, s.reason.length)
    }
}

class FocusStateBuilderTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val rules = RulesEngine(zone)
    private val day = LocalDate.of(2026, 11, 24) // Tuesday
    private fun at(h: Int, m: Int = 0, s: Int = 0): Instant = day.atTime(h, m, s).atZone(zone).toInstant()

    private val plan = Plan(
        version = 1,
        distractions = listOf(
            Surface("instagram.reels", SurfaceMode.HIDE_SECTION),
            Surface("youtube.shorts", SurfaceMode.HIDE_SECTION),
            Surface("app:com.pubg.imobile", SurfaceMode.BLOCK_APP),
        ),
        schedules = listOf(
            Schedule("coaching", "Coaching", setOf(1, 2, 3, 4, 5), "16:00", "18:00", strict = true),
            Schedule("evening", "Evening", setOf(1, 2, 3, 4, 5), "20:00", "21:00", strict = false),
        ),
        keyHolder = KeyHolder("k", "Asha", Relation.FRIEND),
    )
    private val tasks = listOf(
        StudyTask("a", "Old", "Done already", true, Instant.EPOCH),
        StudyTask("b", "Physics · Rotation", "Read 7.2, just the first page", false, Instant.EPOCH),
        StudyTask("c", "3 PYQs on moment of inertia", "Open PYQ book", false, Instant.EPOCH),
    )

    @Test fun strictActiveBlock_locksUntilEnd_andOffersEmergencyUnlock() {
        val block = FocusBlockResolver.resolve(plan, rules, Energy.LOW, at(16, 16), null)
        assertTrue(block.scheduled)
        assertEquals("coaching", block.scheduleId)
        assertEquals(at(18), block.endsAt)
        assertEquals(7200, block.totalSeconds)
        val s = FocusStateBuilder.build(block, plan, tasks, at(17, 44, 30), zone)
        assertEquals("Coaching block", s.blockTitle)
        assertTrue(s.strict)
        assertEquals("15:30", s.clockText)
        assertEquals("2 h", s.totalLabel)
        assertEquals("6:00 pm", s.endsAtLabel)
        assertTrue(s.showEmergencyUnlock)
        assertFalse(s.canFinish)
        assertEquals("Asha", s.keyHolderName)
        assertEquals(listOf("Reels", "Shorts", "BGMI"), s.blocked.map { it.name })
        assertEquals("Read 7.2, just the first page", s.nowStep)
        assertEquals("3 PYQs on moment of inertia", s.next)
    }

    @Test fun strictBlock_canFinishOnceTimeIsUp() {
        val block = FocusBlockResolver.resolve(plan, rules, null, at(16, 16), null)
        val s = FocusStateBuilder.build(block, plan, tasks, at(18, 0, 1), zone)
        assertTrue(s.done)
        assertTrue(s.canFinish)
        assertFalse(s.showEmergencyUnlock)
        assertEquals("00:00", s.clockText)
        assertEquals(0f, s.fraction, 0f)
    }

    @Test fun nonStrictBlock_needsNoUnlock() {
        val block = FocusBlockResolver.resolve(plan, rules, null, at(20, 10), null)
        assertTrue(block.scheduled)
        assertFalse(block.strict)
        val s = FocusStateBuilder.build(block, plan, tasks, at(20, 15), zone)
        assertTrue(s.canFinish)
        assertFalse(s.showEmergencyUnlock)
    }

    @Test fun manualBlock_usesEnergyMinutes() {
        listOf(Energy.LOW to 15, Energy.OKAY to 25, Energy.SHARP to 40, null to 25).forEach { (e, min) ->
            val block = FocusBlockResolver.resolve(plan, rules, e, at(10), "coaching")
            assertFalse(block.scheduled)
            assertEquals("coaching", block.scheduleId)
            assertEquals(min * 60L, block.totalSeconds)
            assertEquals(at(10).plusSeconds(min * 60L), block.endsAt)
            val s = FocusStateBuilder.build(block, plan, emptyList(), at(10), zone)
            assertEquals("$min min", s.totalLabel)
            assertEquals(1f, s.fraction, 0f)
            assertNull(s.blockTitle)
            assertTrue(s.canFinish)
            assertFalse(s.showEmergencyUnlock)
            assertNull(s.nowStep)
            assertEquals(3, s.blocked.size)
        }
    }

    @Test fun selfKeyHolder_isNobodyToAsk() {
        val p = plan.copy(keyHolder = KeyHolder("me", "Me", Relation.SELF))
        assertNull(FocusPolicy.keyHolderName(p))
        val block = FocusBlockResolver.resolve(p, rules, null, at(16, 30), null)
        val s = FocusStateBuilder.build(block, p, tasks, at(16, 30), zone)
        assertNull(s.keyHolderName)
        assertFalse(s.unlockView.asksKeyHolder)
    }

    @Test fun keyHolderNotNotified_isNotNamed() {
        val p = plan.copy(unlockPolicy = UnlockPolicy(notifyKeyHolder = false))
        val block = FocusBlockResolver.resolve(p, rules, null, at(16, 30), null)
        assertNull(FocusStateBuilder.build(block, p, tasks, at(16, 30), zone).keyHolderName)
    }

    @Test fun sessionMath() {
        assertEquals(3, FocusSessionMath.intercepts(baselineOnStartDay = 5, startDayNow = 8))
        assertEquals(0, FocusSessionMath.intercepts(5, 4))
        assertEquals(4, FocusSessionMath.intercepts(5, 6, listOf(3)))
        val start = at(16)
        val end = at(17)
        val grants = listOf(
            UnlockGrant("1", null, at(15, 59), at(16, 14), null),
            UnlockGrant("2", null, at(16), at(16, 15), null),
            UnlockGrant("3", "youtube.shorts", at(16, 30), at(16, 45), "Asha"),
            UnlockGrant("4", null, at(17), at(17, 15), null),
        )
        assertEquals(2, FocusSessionMath.unlocks(grants, start, end))
    }
}
