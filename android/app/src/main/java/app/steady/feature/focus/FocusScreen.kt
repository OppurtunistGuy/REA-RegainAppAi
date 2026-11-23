package app.steady.feature.focus

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.steady.R
import app.steady.data.StudyTask
import app.steady.di.AppContainer
import app.steady.nav.Focus
import app.steady.nav.Navigator
import app.steady.nav.Recap
import app.steady.rules.KeyHolder
import app.steady.rules.Plan
import app.steady.rules.Relation
import app.steady.rules.Schedule
import app.steady.rules.Surface
import app.steady.rules.SurfaceMode
import app.steady.ui.components.GhostButton
import app.steady.ui.components.Pill
import app.steady.ui.components.PillTone
import app.steady.ui.components.PrimaryButton
import app.steady.ui.components.SectionLabel
import app.steady.ui.components.SteadyCard
import app.steady.ui.components.SteadyScreen
import app.steady.ui.components.SubText
import app.steady.ui.theme.MonoFont
import app.steady.ui.theme.Steady
import app.steady.ui.theme.SteadyTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun FocusScreen(container: AppContainer, nav: Navigator, route: Focus) {
    val vm: FocusViewModel = viewModel {
        FocusViewModel(
            planRepo = container.planRepo,
            dayRepo = container.dayRepo,
            sessionRepo = container.sessionRepo,
            unlockRepo = container.unlockRepo,
            rules = container.rules,
            zone = container.zone,
            clock = container.clock,
            routeScheduleId = route.scheduleId,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()

    // Gentle vibration when the block runs out while the screen is open (not if it opened already done).
    val haptics = LocalHapticFeedback.current
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading, state.done) {
        if (state.loading) return@LaunchedEffect
        if (!state.done) wasRunning = true
        else if (wasRunning) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            wasRunning = false
        }
    }
    LaunchedEffect(state.saved) { if (state.saved) nav.resetTo(Recap) }

    FocusContent(
        state = state,
        onFinish = vm::finish,
        onEmergencyUnlock = vm::openUnlock,
    )
    if (state.unlock.isOpen) {
        UnlockBottomSheet(
            state = state,
            onReason = vm::setReason,
            onSurface = vm::chooseSurface,
            onSubmit = vm::submitUnlock,
            onDismiss = vm::dismissUnlock,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FocusContent(
    state: FocusUiState,
    onFinish: () -> Unit,
    onEmergencyUnlock: () -> Unit,
) {
    val c = Steady.colors
    SteadyScreen {
        if (state.loading) return@SteadyScreen
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val title = state.blockTitle ?: stringResource(R.string.focus_block_default)
            SectionLabel(
                when {
                    !state.scheduled -> title
                    state.strict -> stringResource(R.string.focus_label_strict, title)
                    else -> stringResource(R.string.focus_label_flexible, title)
                },
                modifier = Modifier.weight(1f),
            )
            if (state.showEmergencyUnlock) {
                Pill(stringResource(R.string.focus_locked_until, state.endsAtLabel), tone = PillTone.URGE)
            } else if (!state.done) {
                Pill(stringResource(R.string.focus_until, state.endsAtLabel), tone = PillTone.ACCENT)
            }
        }

        FocusRing(state)

        SteadyCard {
            SectionLabel(stringResource(R.string.focus_now))
            Text(
                state.nowStep ?: stringResource(R.string.focus_no_task),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = c.ink,
            )
            when {
                state.next != null -> SubText(stringResource(R.string.focus_next, state.next))
                state.nowStep == null -> SubText(stringResource(R.string.focus_no_task_hint))
            }
        }

        SectionLabel(stringResource(if (state.scheduled) R.string.focus_blocked_now else R.string.focus_blocked_manual))
        if (state.blocked.isEmpty()) {
            SubText(stringResource(R.string.focus_nothing_blocked))
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.blocked.forEach { BlockedChipView(it.name) }
            }
        }

        if (state.done) {
            SubText(stringResource(R.string.focus_done_note))
        } else if (state.scheduled && state.strict) {
            SubText(unlockNote(state))
        }
        state.keyHolderName?.takeIf { state.unlock.pendingRequestId != null }?.let {
            Pill(stringResource(R.string.focus_waiting_on, it), tone = PillTone.ACCENT)
        }

        if (state.showEmergencyUnlock) {
            GhostButton(stringResource(R.string.focus_emergency_unlock), onEmergencyUnlock)
        } else {
            PrimaryButton(
                stringResource(if (state.saving) R.string.focus_saving else R.string.focus_finish),
                onFinish,
                enabled = state.canFinish && !state.saving,
            )
        }
    }
}

@Composable
private fun unlockNote(state: FocusUiState): String {
    val base = if (state.requireReason) stringResource(R.string.focus_unlock_note_reason, state.waitSeconds)
    else stringResource(R.string.focus_unlock_note_wait, state.waitSeconds)
    val told = state.keyHolderName?.let { stringResource(R.string.focus_unlock_note_told, it) }
    return if (told != null) "$base $told" else base
}

/** Prototype `.ring`: 220dp, 21dp track, accent arc shrinking clockwise from 12 o'clock. */
@Composable
private fun FocusRing(state: FocusUiState) {
    val c = Steady.colors
    val description = if (state.done) stringResource(R.string.focus_ring_done)
    else stringResource(R.string.focus_ring_description, state.spokenRemaining, state.totalLabel)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 21.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(c.line, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke))
                if (state.fraction > 0f) {
                    drawArc(
                        c.accent, -90f, 360f * state.fraction, useCenter = false,
                        topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    state.clockText,
                    fontFamily = MonoFont,
                    fontSize = 34.sp,
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                    color = c.ink,
                )
                Text(
                    if (state.done) stringResource(R.string.focus_ring_done)
                    else stringResource(R.string.focus_left_of, state.totalLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                )
            }
        }
    }
}

/** Prototype `.locked span`. */
@Composable
private fun BlockedChipView(name: String) {
    val c = Steady.colors
    Text(
        name,
        modifier = Modifier
            .background(c.urgeSoft, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = c.urge,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnlockBottomSheet(
    state: FocusUiState,
    onReason: (String) -> Unit,
    onSurface: (String?) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Steady.colors.screen,
    ) {
        UnlockSheetContent(state, onReason, onSurface, onSubmit, onDismiss)
    }
}

// ---- Previews ----

private val previewZone: ZoneId = ZoneId.of("Asia/Kolkata")
private val previewDay: LocalDate = LocalDate.of(2026, 11, 24) // a Tuesday
private fun previewAt(h: Int, m: Int = 0, s: Int = 0): Instant = previewDay.atTime(h, m, s).atZone(previewZone).toInstant()

internal val previewPlan = Plan(
    version = 1,
    distractions = listOf(
        Surface("instagram.reels", SurfaceMode.HIDE_SECTION),
        Surface("youtube.shorts", SurfaceMode.HIDE_SECTION),
        Surface("snapchat.spotlight", SurfaceMode.HIDE_SECTION),
        Surface("app:com.pubg.imobile", SurfaceMode.BLOCK_APP),
    ),
    schedules = listOf(Schedule("coaching", "Coaching", setOf(1, 2, 3, 4, 5), "16:00", "18:00")),
    keyHolder = KeyHolder("k1", "Asha", Relation.FRIEND),
)

private val previewTasks = listOf(
    StudyTask("t1", "Physics · Rotational motion", "Read 7.2, just the first page", false, Instant.EPOCH),
    StudyTask("t2", "3 PYQs on moment of inertia", "Open the PYQ book", false, Instant.EPOCH),
)

internal fun previewFocusState(
    now: Instant = previewAt(17, 44, 30),
    unlock: UnlockSheetState = UnlockSheetState(),
): FocusUiState {
    val rules = app.steady.rules.RulesEngine(previewZone)
    val block = FocusBlockResolver.resolve(previewPlan, rules, null, previewAt(16, 16), null)
    return FocusStateBuilder.build(block, previewPlan, previewTasks, now, previewZone, unlock)
}

@Preview(name = "Focus · strict · light", showBackground = true, heightDp = 760)
@Composable
private fun FocusStrictLightPreview() {
    SteadyTheme(darkTheme = false) { FocusContent(previewFocusState(), {}, {}) }
}

@Preview(name = "Focus · strict · dark", showBackground = true, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FocusStrictDarkPreview() {
    SteadyTheme(darkTheme = true) { FocusContent(previewFocusState(), {}, {}) }
}

@Preview(name = "Focus · done · dark", showBackground = true, heightDp = 760, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FocusDoneDarkPreview() {
    SteadyTheme(darkTheme = true) { FocusContent(previewFocusState(now = previewAt(18, 0)), {}, {}) }
}
