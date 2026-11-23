package app.steady.feature.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.steady.di.AppContainer
import app.steady.enforcement.rememberAccessibilityEnabled
import app.steady.nav.Disclosure
import app.steady.nav.Navigator
import app.steady.nav.Today
import app.steady.rules.Relation
import app.steady.rules.Schedule
import app.steady.ui.Format
import app.steady.ui.components.GhostButton
import app.steady.ui.components.LinkButton
import app.steady.ui.components.MinTouch
import app.steady.ui.components.OptionChip
import app.steady.ui.components.PrimaryButton
import app.steady.ui.components.ScreenTitle
import app.steady.ui.components.SectionLabel
import app.steady.ui.components.SteadyScreen
import app.steady.ui.components.SubText
import app.steady.ui.theme.Steady
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun SetupScreen(container: AppContainer, nav: Navigator) {
    val vm: SetupViewModel = viewModel { SetupViewModel(container.planRepo, container.entitlements) }
    val state by vm.state.collectAsStateWithLifecycle()
    val accessibilityOn by rememberAccessibilityEnabled()

    // The saved plan reaches BlockScheduler through SteadyApp's plan collector (alarm + FocusService).
    LaunchedEffect(state.saved) {
        if (state.saved) {
            vm.consumeSaved()
            nav.resetTo(Today)
            // Without the accessibility service nothing gets hidden: explain it right away.
            if (!accessibilityOn) nav.go(Disclosure)
        }
    }

    SetupContent(
        state = state,
        onToggleDistraction = vm::toggleDistraction,
        onToggleSchedule = vm::toggleSchedule,
        onEditSchedule = vm::editSchedule,
        onNewSchedule = vm::newSchedule,
        onRelation = vm::setRelation,
        onName = vm::setKeyHolderName,
        onSave = vm::save,
        lockedUntilLabel = state.lockedUntil?.let {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(container.zone).format(it)
        },
    )

    state.editor?.let { draft ->
        ScheduleEditorDialog(
            draft = draft,
            canDelete = draft.id != null && !state.isPreset(draft.id),
            onChange = vm::updateDraft,
            onSave = vm::saveDraft,
            onDelete = { draft.id?.let(vm::deleteSchedule) },
            onDismiss = vm::cancelDraft,
        )
    }
}

fun scheduleSummary(s: Schedule): String = "${s.label} ${Format.timeRange(s.start, s.end)} ${Format.days(s.days)}"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupContent(
    state: SetupUiState,
    onToggleDistraction: (String) -> Unit,
    onToggleSchedule: (String) -> Unit,
    onEditSchedule: (String) -> Unit,
    onNewSchedule: () -> Unit,
    onRelation: (Relation) -> Unit,
    onName: (String) -> Unit,
    onSave: () -> Unit,
    lockedUntilLabel: String? = null,
) {
    SteadyScreen {
        SectionLabel(if (state.isEdit) "Edit plan" else "Setup · 90 seconds")
        ScreenTitle("What pulls you away?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.distractions.forEach { d ->
                OptionChip(d.label, d.surfaceId in state.selectedDistractions, { onToggleDistraction(d.surfaceId) })
            }
        }

        SectionLabel("When should Steady start on its own?")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.schedules.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OptionChip(
                        text = scheduleSummary(s),
                        selected = s.id in state.selectedSchedules,
                        onClick = { onToggleSchedule(s.id) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { onEditSchedule(s.id) },
                        modifier = Modifier
                            .heightIn(min = MinTouch)
                            .semantics { contentDescription = "Edit ${s.label} times" },
                    ) { Text("Edit", color = Steady.colors.accent) }
                }
            }
            LinkButton("+ Add your own time", onNewSchedule)
            if (state.limitHit && state.scheduleLimit != null) {
                SubText("The free plan runs ${state.scheduleLimit} schedules at once. Turn one off to pick another.")
            }
        }

        SectionLabel("Who holds the key?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionChip("Me, 30 s wait", state.relation == Relation.SELF, { onRelation(Relation.SELF) }, single = true)
            OptionChip("A friend", state.relation == Relation.FRIEND, { onRelation(Relation.FRIEND) }, single = true)
            OptionChip("A parent", state.relation == Relation.PARENT, { onRelation(Relation.PARENT) }, single = true)
        }
        if (state.needsName) {
            OutlinedTextField(
                value = state.keyHolderName,
                onValueChange = onName,
                label = { Text(if (state.relation == Relation.PARENT) "Parent's name" else "Friend's name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            SubText("They'll see your emergency unlock requests in Steady once they accept your invite. You can invite them later.")
        } else {
            SubText("Emergency unlock asks for a 30-second wait and a typed reason.")
        }

        SubText("Reels and Shorts are hidden inside the app. Messages and Stories still work.")
        PrimaryButton(if (state.isEdit) "Save my plan" else "Start my plan", onSave, enabled = state.canSave)
        if (lockedUntilLabel != null) {
            SubText(
                "A strict block is running, so this change would make it easier to break. " +
                    "You can loosen your plan after it ends at $lockedUntilLabel. Adding things works any time.",
            )
        }
        if (!state.loading && !state.canSave && !state.saving) {
            SubText(
                when {
                    state.selectedDistractions.isEmpty() -> "Pick at least one thing that pulls you away."
                    state.selectedSchedules.isEmpty() -> "Pick at least one time for Steady to start."
                    else -> "Add your key holder's name."
                },
            )
        }
    }
}

private val weekDays = (1..7).toList()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleEditorDialog(
    draft: ScheduleDraft,
    canDelete: Boolean,
    onChange: (ScheduleDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "New schedule" else "Edit schedule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.label,
                    onValueChange = { onChange(draft.copy(label = it.take(30))) },
                    label = { Text("Name, e.g. Sunday revision") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionLabel("Days")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    weekDays.forEach { d ->
                        OptionChip(
                            Format.dayName(d),
                            d in draft.days,
                            { onChange(draft.copy(days = if (d in draft.days) draft.days - d else draft.days + d)) },
                        )
                    }
                }
                SectionLabel("Time")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Starts ${Format.time(draft.start)}", { picking = true }, Modifier.weight(1f))
                    GhostButton("Ends ${Format.time(draft.end)}", { picking = false }, Modifier.weight(1f))
                }
                if (!draft.end.isAfter(draft.start) && draft.end != draft.start) {
                    SubText("Ends after midnight.")
                }
                if (canDelete) {
                    TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = MinTouch)) {
                        Text("Delete this schedule", color = Steady.colors.urge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = draft.isValid, modifier = Modifier.heightIn(min = MinTouch)) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) { Text("Cancel") }
        },
    )

    picking?.let { isStart ->
        val initial = if (isStart) draft.start else draft.end
        val tp = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (isStart) "Start time" else "End time") },
            text = { TimePicker(state = tp, modifier = Modifier.padding(top = 8.dp)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val t = LocalTime.of(tp.hour, tp.minute)
                        onChange(if (isStart) draft.copy(start = t) else draft.copy(end = t))
                        picking = null
                    },
                    modifier = Modifier.heightIn(min = MinTouch),
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }, modifier = Modifier.heightIn(min = MinTouch)) { Text("Cancel") }
            },
        )
    }
}
