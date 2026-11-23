package app.steady.feature.recap

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.steady.R
import app.steady.data.CardRepository
import app.steady.data.EmptyPlan
import app.steady.data.PlanRepository
import app.steady.data.SessionRepository
import app.steady.di.AppContainer
import app.steady.feature.learning.Minutes
import app.steady.feature.learning.learningTicker
import app.steady.learning.FocusSession
import app.steady.nav.Navigator
import app.steady.nav.Partner
import app.steady.nav.Today
import app.steady.rules.Plan
import app.steady.ui.Format
import app.steady.ui.components.GhostButton
import app.steady.ui.components.Pill
import app.steady.ui.components.PillTone
import app.steady.ui.components.PrimaryButton
import app.steady.ui.components.ScreenTitle
import app.steady.ui.components.SectionLabel
import app.steady.ui.components.SteadyCard
import app.steady.ui.components.SteadyScreen
import app.steady.ui.components.SubText
import app.steady.ui.theme.MonoFont
import app.steady.ui.theme.Steady
import app.steady.ui.theme.SteadyTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

private data class RecapDraft(val lines: List<String> = List(RecapLogic.LINES) { "" }, val saved: SavedRecall? = null)

/**
 * Recall-to-cards is core (free): the `notes_to_cards` entitlement is only for bulk notes import, so it is
 * not checked here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecapViewModel(
    private val sessionRepo: SessionRepository,
    private val cardRepo: CardRepository,
    private val planRepo: PlanRepository,
    private val clock: Clock,
    private val zone: ZoneId,
    now: Flow<Instant>,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val draft = MutableStateFlow(RecapDraft())
    private var saving = false
    private val nowShared = now.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    private val intercepts = nowShared.map { it.atZone(zone).toLocalDate() }.distinctUntilChanged()
        .flatMapLatest { sessionRepo.interceptsOn(it) }.onStart { emit(0) }

    val state: StateFlow<RecapUiState> =
        combine(sessionRepo.sessions, intercepts, nowShared, draft) { s, i, t, d ->
            RecapStateBuilder.build(s, i, t, zone, d.lines, d.saved)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecapUiState())

    fun setLine(index: Int, value: String) {
        if ((draft.value.saved?.count ?: 0) > 0) return
        draft.update { it.copy(lines = RecapLogic.setLine(it.lines, index, value), saved = null) }
    }

    fun save() {
        val d = draft.value
        if (saving || !RecapUiState(lines = d.lines, saved = d.saved).canSave) return
        saving = true
        viewModelScope.launch {
            val sessions: List<FocusSession> = sessionRepo.sessions.first()
            val plan: Plan = runCatching { planRepo.plan.first() }.getOrDefault(EmptyPlan)
            val (cards, saved) = RecapLogic.toCards(d.lines, RecapLogic.deckFor(sessions, plan), clock.instant(), zone, idGen)
            if (cards.isNotEmpty()) cardRepo.upsert(cards)
            draft.update { it.copy(saved = saved) }
            saving = false
        }
    }
}

@Composable
fun RecapScreen(container: AppContainer, nav: Navigator) {
    val vm: RecapViewModel = viewModel {
        RecapViewModel(
            container.sessionRepo, container.cardRepo, container.planRepo, container.clock, container.zone,
            learningTicker(container.clock, 30_000),
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    RecapContent(
        state = state,
        onLine = vm::setLine,
        onSave = vm::save,
        onPartner = { nav.go(Partner) },
        onDone = { nav.resetTo(Today) },
    )
}

@Composable
fun RecapContent(
    state: RecapUiState,
    onLine: (Int, String) -> Unit,
    onSave: () -> Unit,
    onPartner: () -> Unit,
    onDone: () -> Unit,
) {
    val c = Steady.colors
    SteadyScreen {
        SectionLabel(
            state.blockMinutes?.let { stringResource(R.string.recap_block_done, it) } ?: stringResource(R.string.recap_block_done_plain),
        )
        ScreenTitle(stringResource(R.string.recap_title))

        val hints = listOf(R.string.recap_hint_1, R.string.recap_hint_2, R.string.recap_hint_3)
        state.lines.forEachIndexed { i, line ->
            OutlinedTextField(
                value = line,
                onValueChange = { onLine(i, it) },
                label = { Text(stringResource(R.string.recap_line_label, i + 1)) },
                placeholder = { Text(stringResource(hints[i % hints.size]), color = c.muted) },
                singleLine = true,
                readOnly = state.linesLocked,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = if (i < state.lines.lastIndex) ImeAction.Next else ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val saved = state.saved
        if (saved != null && saved.count > 0) {
            SteadyCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.recap_turned_into, saved.count, saved.count),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.ink,
                        modifier = Modifier.weight(1f),
                    )
                    if (saved.dayLabels.isNotEmpty()) Pill(saved.dayLabels.joinToString(", "), tone = PillTone.ACCENT)
                }
            }
        } else {
            if (saved != null) SubText(stringResource(R.string.recap_no_cards))
            GhostButton(stringResource(R.string.recap_save), onSave, enabled = state.canSave)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat(Minutes.short(state.focusedMinutesToday), stringResource(R.string.recap_focused_today), Modifier.weight(1f))
            Stat(state.urgesToday.toString(), stringResource(R.string.recap_urges_caught), Modifier.weight(1f))
            Stat(state.unlocksToday.toString(), stringResource(R.string.recap_unlocks), Modifier.weight(1f))
        }

        SectionLabel(stringResource(R.string.recap_week_label, state.goalTarget))
        WeekRow(state.week)
        SubText(stringResource(R.string.recap_no_streak))
        if (state.goalMet) {
            SubText(stringResource(R.string.recap_goal_met))
        } else if (state.week.isNotEmpty()) {
            SubText(pluralStringResource(R.plurals.recap_goal_left, state.goalRemaining, state.goalRemaining))
        }

        PrimaryButton(stringResource(R.string.recap_partner), onPartner)
        GhostButton(stringResource(R.string.recap_done), onDone)
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    val c = Steady.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .border(1.dp, c.line, shape)
            .background(c.surface, shape)
            .padding(horizontal = 6.dp, vertical = 10.dp)
            .clearAndSetSemantics { contentDescription = "$value $label" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFamily = MonoFont), color = c.ink)
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.muted, textAlign = TextAlign.Center)
    }
}

/** Prototype `.week`: seven squares, calm green for days that counted. */
@Composable
fun WeekRow(week: List<WeekCell>, modifier: Modifier = Modifier) {
    val c = Steady.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        week.forEach { cell ->
            val day = Format.dayName(cell.date.dayOfWeek.value)
            val desc = stringResource(if (cell.done) R.string.recap_week_day_done else R.string.recap_week_day_open, day)
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .background(if (cell.done) c.calm else c.line, shape)
                    .then(if (cell.isToday) Modifier.border(2.dp, c.accent, shape) else Modifier)
                    .clearAndSetSemantics { contentDescription = desc },
                contentAlignment = Alignment.Center,
            ) {
                Text(cell.letter, style = MaterialTheme.typography.labelSmall, color = if (cell.done) c.screen else c.muted)
            }
        }
    }
}

// ---- previews ----

private fun previewState(): RecapUiState {
    val zone = ZoneId.of("Asia/Kolkata")
    val now = Instant.parse("2026-11-24T11:12:00Z") // Tue 4:42 pm IST
    val mon = now.minusSeconds(86_400)
    val sessions = listOf(
        FocusSession("a", null, mon.minusSeconds(3_000), mon, 1, 0),
        FocusSession("b", null, now.minusSeconds(1_500), now, 2, 0),
        FocusSession("c", null, now.minusSeconds(6_000), now.minusSeconds(4_620), 0, 0),
    )
    return RecapStateBuilder.build(
        sessions, interceptsToday = 2, now = now, zone = zone,
        lines = listOf("I depends on how mass is spread", "Parallel axis: I = I_cm + Md²", ""),
        saved = SavedRecall(2, listOf("Wed", "Sat")),
    )
}

@Preview(name = "Recap · light", showBackground = true)
@Preview(name = "Recap · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecapPreview() = SteadyTheme { RecapContent(previewState(), { _, _ -> }, {}, {}, {}) }

@Preview(name = "Recap empty · light", showBackground = true)
@Preview(name = "Recap empty · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecapEmptyPreview() = SteadyTheme {
    RecapContent(previewState().copy(lines = List(3) { "" }, saved = null), { _, _ -> }, {}, {}, {})
}
