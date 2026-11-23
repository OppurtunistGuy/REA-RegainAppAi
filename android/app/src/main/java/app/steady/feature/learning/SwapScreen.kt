package app.steady.feature.learning

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.steady.R
import app.steady.data.CardRepository
import app.steady.di.AppContainer
import app.steady.learning.Card
import app.steady.learning.Rating
import app.steady.learning.Scheduler
import app.steady.nav.Navigator
import app.steady.ui.components.GhostButton
import app.steady.ui.components.Pill
import app.steady.ui.components.PillTone
import app.steady.ui.components.PrimaryButton
import app.steady.ui.components.ScreenTitle
import app.steady.ui.components.SectionLabel
import app.steady.ui.components.SteadyScreen
import app.steady.ui.components.SubText
import app.steady.ui.theme.SteadyTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Emits the current time now and then every [periodMs]. */
internal fun learningTicker(clock: Clock, periodMs: Long): Flow<Instant> = flow {
    while (true) {
        emit(clock.instant())
        delay(periodMs)
    }
}

class SwapViewModel(
    private val cardRepo: CardRepository,
    private val clock: Clock,
    private val zone: ZoneId,
    now: Flow<Instant>,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val cards = MutableStateFlow<List<Card>?>(null)
    private val session = MutableStateFlow(SwapSession(startedAt = clock.instant()))

    init {
        viewModelScope.launch {
            cardRepo.cards.collect { list ->
                cards.value = list
                session.update { SwapReducer.ensurePicked(it, list, clock.instant()) }
            }
        }
    }

    val state: StateFlow<SwapUiState> = combine(cards, session, now) { c, s, t -> SwapStateBuilder.build(c, s, t, zone) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SwapStateBuilder.build(null, session.value, clock.instant(), zone))

    fun reveal() = session.update(SwapReducer::reveal)

    fun rate(rating: Rating) {
        val s = session.value
        if (!SwapReducer.canRate(s)) return
        val card = cards.value?.firstOrNull { it.id == s.cardId } ?: return
        val updated = ReviewFlow.rate(card, rating, clock.instant())
        session.value = SwapReducer.rated(s, rating)
        viewModelScope.launch { cardRepo.upsert(listOf(updated)) }
    }

    fun addSamples() {
        viewModelScope.launch { cardRepo.upsert(StarterCards.physics(clock.instant(), idGen)) }
    }

    fun showAddForm(show: Boolean) = session.update { it.copy(showAddForm = show, draft = CardDraft()) }
    fun setFront(v: String) = session.update { it.copy(draft = it.draft.withFront(v)) }
    fun setBack(v: String) = session.update { it.copy(draft = it.draft.withBack(v)) }

    fun saveDraft() {
        val card = CardEditing.apply(session.value.draft, cards.value.orEmpty(), clock.instant(), idGen) ?: return
        session.update { it.copy(showAddForm = false, draft = CardDraft()) }
        viewModelScope.launch { cardRepo.upsert(listOf(card)) }
    }
}

@Composable
fun SwapScreen(container: AppContainer, nav: Navigator) {
    val vm: SwapViewModel = viewModel {
        SwapViewModel(container.cardRepo, container.clock, container.zone, learningTicker(container.clock, 1_000))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    SwapContent(
        state = state,
        onReveal = vm::reveal,
        onRate = vm::rate,
        onAddSamples = vm::addSamples,
        onShowAddForm = vm::showAddForm,
        onFront = vm::setFront,
        onBack = vm::setBack,
        onSaveCard = vm::saveDraft,
        onDone = nav::back,
    )
}

@Composable
fun SwapContent(
    state: SwapUiState,
    onReveal: () -> Unit,
    onRate: (Rating) -> Unit,
    onAddSamples: () -> Unit,
    onShowAddForm: (Boolean) -> Unit,
    onFront: (String) -> Unit,
    onBack: (String) -> Unit,
    onSaveCard: () -> Unit,
    onDone: () -> Unit,
) {
    SteadyScreen {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.swap_label))
            if (!state.isEmpty) Pill(state.timerLabel, tone = PillTone.ACCENT)
        }
        val card = state.card
        when {
            state.loading -> Unit
            card == null -> EmptySwap(state, onAddSamples, onShowAddForm, onFront, onBack, onSaveCard)
            else -> {
                TimerBar(state.timerFraction, stringResource(R.string.swap_timer_desc, state.timerLabel))
                Flashcard(card, revealed = state.phase != SwapPhase.QUESTION, onReveal = onReveal)
                if (state.phase != SwapPhase.QUESTION) {
                    RatingRow(selected = state.rating, enabled = state.phase == SwapPhase.ANSWER, onRate = onRate)
                }
                when {
                    state.rating == Rating.AGAIN -> SubText(stringResource(R.string.swap_rated_again))
                    state.phase == SwapPhase.RATED -> SubText(stringResource(R.string.swap_rated_next, state.nextDueLabel.orEmpty()))
                    else -> SubText(stringResource(R.string.swap_counts_toward, state.reviewDay))
                }
                if (state.timeUp && state.phase != SwapPhase.RATED) SubText(stringResource(R.string.swap_time_up))
            }
        }
        val deck = state.deckName
        PrimaryButton(
            if (deck != null) stringResource(R.string.swap_back_to, deck) else stringResource(R.string.swap_back_to_focus),
            onDone,
        )
    }
}

@Composable
private fun EmptySwap(
    state: SwapUiState,
    onAddSamples: () -> Unit,
    onShowAddForm: (Boolean) -> Unit,
    onFront: (String) -> Unit,
    onBack: (String) -> Unit,
    onSaveCard: () -> Unit,
) {
    ScreenTitle(stringResource(R.string.swap_empty_title))
    SubText(stringResource(R.string.swap_empty_body))
    if (state.showAddForm) {
        CardEditorForm(
            title = stringResource(R.string.swap_write_card),
            draft = state.draft,
            onFront = onFront,
            onBack = onBack,
            onSave = onSaveCard,
            onCancel = { onShowAddForm(false) },
        )
    } else {
        PrimaryButton(stringResource(R.string.swap_add_samples), onAddSamples)
        GhostButton(stringResource(R.string.swap_write_card), { onShowAddForm(true) })
    }
}

// ---- previews ----

internal object PreviewData {
    val now: Instant = Instant.parse("2026-11-24T10:37:00Z") // Tue 4:07 pm in Asia/Kolkata
    val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    val cards: List<Card> = StarterCards.physics(now) { "c" }.mapIndexed { i, c -> c.copy(id = "c$i") }
    val card: Card = cards.first().copy(state = Scheduler.newState(now))
}

@Composable
private fun SwapPreviewBody(state: SwapUiState) =
    SwapContent(state, {}, {}, {}, {}, {}, {}, {}, {})

@Preview(name = "Swap · light", showBackground = true)
@Preview(name = "Swap · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SwapPreview() = SteadyTheme {
    SwapPreviewBody(
        SwapUiState(
            loading = false, card = PreviewData.card, phase = SwapPhase.ANSWER, secondsLeft = 42,
            reviewDay = "Thursday’s", deckName = Decks.PHYSICS,
        ),
    )
}

@Preview(name = "Swap empty · light", showBackground = true)
@Preview(name = "Swap empty · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SwapEmptyPreview() = SteadyTheme { SwapPreviewBody(SwapUiState(loading = false)) }
