package app.steady.feature.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.steady.data.StudyTask
import app.steady.di.AppContainer
import app.steady.nav.Focus
import app.steady.nav.Insights
import app.steady.nav.Intercept
import app.steady.nav.Navigator
import app.steady.nav.Review
import app.steady.nav.Settings
import app.steady.rules.Energy
import app.steady.ui.components.GhostButton
import app.steady.ui.components.LinkButton
import app.steady.ui.components.MinTouch
import app.steady.ui.components.OptionChip
import app.steady.ui.components.Pill
import app.steady.ui.components.PillTone
import app.steady.ui.components.PrimaryButton
import app.steady.ui.components.ScreenTitle
import app.steady.ui.components.SectionLabel
import app.steady.ui.components.SteadyCard
import app.steady.ui.components.SteadyScreen
import app.steady.ui.components.SubText
import app.steady.ui.theme.Steady

@Composable
fun TodayScreen(container: AppContainer, nav: Navigator) {
    val vm: TodayViewModel = viewModel {
        TodayViewModel(container.planRepo, container.dayRepo, container.rules, container.zone, tickerFlow(container.clock))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    TodayContent(
        state = state,
        onEnergy = vm::setEnergy,
        onDraftTitle = vm::setDraftTitle,
        onDraftStep = vm::setDraftStep,
        onAddTask = vm::addTask,
        onToggleTask = vm::toggleTask,
        onStartNow = { nav.go(Focus(scheduleId = state.next?.takeIf { it.isActive }?.scheduleId)) },
        onTryIntercept = { nav.go(Intercept(surfaceId = "instagram.reels")) },
        onReview = { nav.go(Review) },
        onInsights = { nav.go(Insights) },
        onSettings = { nav.go(Settings) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodayContent(
    state: TodayUiState,
    onEnergy: (Energy) -> Unit,
    onDraftTitle: (String) -> Unit,
    onDraftStep: (String) -> Unit,
    onAddTask: () -> Unit,
    onToggleTask: (String, Boolean) -> Unit,
    onStartNow: () -> Unit,
    onTryIntercept: () -> Unit,
    onReview: () -> Unit,
    onInsights: () -> Unit,
    onSettings: () -> Unit,
) {
    SteadyScreen {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(state.dateLabel)
            Pill(if (state.autoStartOn) "Auto-start on" else "No schedules", tone = if (state.autoStartOn) PillTone.CALM else PillTone.URGE)
        }

        ScreenTitle("How’s your energy?")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Energy.entries.forEach { e ->
                OptionChip(
                    text = e.label(),
                    selected = state.energy == e,
                    onClick = { onEnergy(e) },
                    single = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SteadyCard {
            SectionLabel("Your day")
            DayStripBar(state.strip)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                state.strip.legend.forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = Steady.colors.muted)
                }
            }
        }

        SteadyCard {
            val next = state.next
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    next?.let { "${it.label} · ${it.timeRange}" } ?: "No blocks coming up",
                    style = MaterialTheme.typography.titleMedium,
                    color = Steady.colors.ink,
                    modifier = Modifier.weight(1f),
                )
                if (next != null) Pill(next.status, tone = if (next.isActive) PillTone.URGE else PillTone.ACCENT)
            }
            val energyWord = (state.energy ?: Energy.OKAY).label()
            SubText("$energyWord energy: ${state.blockMinutes}-min block")
            if (state.tasks.isEmpty()) {
                SubText("Add what you'll study and one tiny first step.")
            }
            state.tasks.forEach { task -> TaskRow(task, onToggleTask) }
        }

        SteadyCard {
            SectionLabel("Add a task")
            OutlinedTextField(
                value = state.draftTitle,
                onValueChange = onDraftTitle,
                label = { Text("Task, e.g. Physics · Rotational motion") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.draftStep,
                onValueChange = onDraftStep,
                label = { Text("Tiny first step (1 minute)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onAddTask, enabled = state.canAddTask, modifier = Modifier.heightIn(min = MinTouch)) {
                Text("Add task")
            }
        }

        PrimaryButton("Start now", onStartNow)
        GhostButton("Try opening Instagram", onTryIntercept)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LinkButton("Review cards", onReview)
            LinkButton("Insights", onInsights)
            LinkButton("Settings", onSettings)
        }
    }
}

private fun Energy.label() = when (this) {
    Energy.LOW -> "Low"
    Energy.OKAY -> "Okay"
    Energy.SHARP -> "Sharp"
}

@Composable
private fun DayStripBar(strip: DayStrip) {
    val c = Steady.colors
    val description = if (strip.segments.none { it.isBlock }) "No focus blocks today"
    else "Focus blocks today: " + strip.legend.drop(1).dropLast(1).joinToString(", ")
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(c.line)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Row(Modifier.fillMaxWidth().height(12.dp)) {
            strip.segments.forEach { seg ->
                if (seg.fraction > 0f) {
                    Box(
                        Modifier
                            .weight(seg.fraction)
                            .fillMaxHeight()
                            .background(if (seg.isBlock) c.accent else c.line),
                    )
                }
            }
        }
        strip.nowFraction?.let { f ->
            Box(
                Modifier
                    .offset(x = maxWidth * f)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(c.ink),
            )
        }
    }
}

@Composable
private fun TaskRow(task: StudyTask, onToggle: (String, Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .toggleable(value = task.done, role = Role.Checkbox, onValueChange = { onToggle(task.id, it) })
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(
            checked = task.done,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = Steady.colors.calm, uncheckedColor = Steady.colors.line),
        )
        Column(Modifier.weight(1f)) {
            Text(
                task.firstStep,
                style = MaterialTheme.typography.bodyMedium,
                color = Steady.colors.ink,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
            )
            Text(
                "First step for: ${task.title}",
                style = MaterialTheme.typography.bodySmall,
                color = Steady.colors.muted,
            )
        }
    }
}
