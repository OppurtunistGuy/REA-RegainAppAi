package app.steady.feature.learning

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.steady.R
import app.steady.data.CardRepository
import app.steady.di.AppContainer
import app.steady.learning.Card
import app.steady.learning.Rating
import app.steady.nav.Navigator
import app.steady.ui.components.GhostButton
import app.steady.ui.components.LinkButton
import app.steady.ui.components.Pill
import app.steady.ui.components.PillTone
import app.steady.ui.components.PrimaryButton
import app.steady.ui.components.ScreenTitle
import app.steady.ui.components.SectionLabel
import app.steady.ui.components.SteadyCard
import app.steady.ui.components.SteadyScreen
import app.steady.ui.components.SubText
import app.steady.ui.theme.Steady
import app.steady.ui.theme.SteadyTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Review queue plus a simple card list with add, edit and delete (with a confirm step).
 */
class ReviewViewModel(
    private val cardRepo: CardRepository,
    private val clock: Clock,
    private val zone: ZoneId,
    now: Flow<Instant>,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val cards = MutableStateFlow<List<Card>?>(null)
    private val session = MutableStateFlow(ReviewSession())

    init {
        viewModelScope.launch {
            cardRepo.cards.collect { list ->
                cards.value = list
                session.update { ReviewQueue.ensureStarted(it, list, clock.instant()) }
            }
        }
    }

    val state: StateFlow<ReviewUiState> = combine(cards, session, now) { c, s, t -> ReviewStateBuilder.build(c, s, t, zone) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    fun reveal() = session.update(ReviewQueue::reveal)

    fun rate(rating: Rating) {
        val s = session.value
        if (!s.revealed) return
        val card = ReviewQueue.currentId(s)?.let { id -> cards.value?.firstOrNull { it.id == id } } ?: return
        val updated = ReviewFlow.rate(card, rating, clock.instant())
        session.value = ReviewQueue.advance(s)
        viewModelScope.launch { cardRepo.upsert(listOf(updated)) }
    }

    /** Re-reads what is due now (e.g. "Again" cards after 10 minutes). */
    fun checkAgain() = session.update { ReviewQueue.ensureStarted(ReviewQueue.restart(it), cards.value.orEmpty(), clock.instant()) }

    fun startAdd() = session.update { it.copy(editor = CardDraft()) }
    fun startEdit(card: Card) = session.update { it.copy(editor = CardDraft.of(card)) }
    fun cancelEdit() = session.update { it.copy(editor = null) }
    fun setFront(v: String) = session.update { s -> s.copy(editor = s.editor?.withFront(v)) }
    fun setBack(v: String) = session.update { s -> s.copy(editor = s.editor?.withBack(v)) }

    fun askDelete(card: Card) = session.update { it.copy(confirmDeleteId = card.id) }
    fun cancelDelete() = session.update { it.copy(confirmDeleteId = null) }

    /** Deletes the card being confirmed; it also leaves the frozen queue so the sitting continues. */
    fun confirmDelete() {
        val id = session.value.confirmDeleteId ?: return
        session.update { s -> ReviewQueue.remove(s, id).copy(confirmDeleteId = null, editor = s.editor?.takeIf { it.id != id }) }
        viewModelScope.launch { cardRepo.delete(listOf(id)) }
    }

    fun saveEdit() {
        val draft = session.value.editor ?: return
        val card = CardEditing.apply(draft, cards.value.orEmpty(), clock.instant(), idGen) ?: return
        // If nothing is left to review, re-read the queue once the new card is stored.
        session.update { s -> s.copy(editor = null).let { if (ReviewQueue.currentId(it) == null) ReviewQueue.restart(it) else it } }
        viewModelScope.launch { cardRepo.upsert(listOf(card)) }
    }
}

@Composable
fun ReviewScreen(container: AppContainer, nav: Navigator) {
    val vm: ReviewViewModel = viewModel {
        ReviewViewModel(container.cardRepo, container.clock, container.zone, learningTicker(container.clock, 30_000))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    ReviewContent(
        state = state,
        onReveal = vm::reveal,
        onRate = vm::rate,
        onCheckAgain = vm::checkAgain,
        onAdd = vm::startAdd,
        onEdit = vm::startEdit,
        onFront = vm::setFront,
        onBack = vm::setBack,
        onSave = vm::saveEdit,
        onCancel = vm::cancelEdit,
        onDone = nav::back,
        onDelete = vm::askDelete,
    )
    state.confirmDelete?.let { card ->
        AlertDialog(
            onDismissRequest = vm::cancelDelete,
            title = { Text(stringResource(R.string.review_delete_title)) },
            text = { Text(stringResource(R.string.review_delete_body, card.front)) },
            confirmButton = { TextButton(onClick = vm::confirmDelete) { Text(stringResource(R.string.review_delete)) } },
            dismissButton = { TextButton(onClick = vm::cancelDelete) { Text(stringResource(R.string.review_delete_cancel)) } },
        )
    }
}

@Composable
fun ReviewContent(
    state: ReviewUiState,
    onReveal: () -> Unit,
    onRate: (Rating) -> Unit,
    onCheckAgain: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Card) -> Unit,
    onFront: (String) -> Unit,
    onBack: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onDelete: (Card) -> Unit = {},
) {
    SteadyScreen {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(stringResource(R.string.review_label))
            if (state.current != null) Pill(stringResource(R.string.review_progress, state.position, state.total), tone = PillTone.ACCENT)
        }
        val current = state.current
        when {
            state.loading -> Unit
            current != null -> {
                ScreenTitle(stringResource(R.string.review_title))
                Flashcard(current, revealed = state.revealed, onReveal = onReveal)
                if (state.revealed) RatingRow(selected = null, enabled = true, onRate = onRate)
            }
            else -> DoneCard(state, onCheckAgain)
        }

        val editor = state.editor
        if (editor != null) {
            CardEditorForm(
                title = stringResource(if (editor.id == null) R.string.review_add_card else R.string.review_edit_card),
                draft = editor,
                onFront = onFront,
                onBack = onBack,
                onSave = onSave,
                onCancel = onCancel,
            )
        } else if (!state.loading) {
            GhostButton(stringResource(R.string.review_add_card), onAdd)
        }

        if (state.cards.isNotEmpty()) {
            SectionLabel(stringResource(R.string.review_your_cards))
            state.cards.forEach { card -> CardRow(card, state.dueLabels[card.id].orEmpty(), onEdit, onDelete) }
        }
        PrimaryButton(stringResource(R.string.learning_back), onDone)
    }
}

@Composable
private fun DoneCard(state: ReviewUiState, onCheckAgain: () -> Unit) {
    SteadyCard {
        Text(
            stringResource(if (state.hadQueue) R.string.review_done_title else R.string.review_nothing_due),
            style = MaterialTheme.typography.titleMedium,
            color = Steady.colors.ink,
        )
        val next = state.nextDue
        if (next != null) SubText(stringResource(R.string.review_next_due, next)) else SubText(stringResource(R.string.review_no_cards))
        if (next != null) LinkButton(stringResource(R.string.review_check_again), onCheckAgain)
    }
}

@Composable
private fun CardRow(card: Card, dueLabel: String, onEdit: (Card) -> Unit, onDelete: (Card) -> Unit) {
    SteadyCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.front, style = MaterialTheme.typography.bodyMedium, color = Steady.colors.ink)
                Text(card.back, style = MaterialTheme.typography.bodySmall, color = Steady.colors.accent)
                Text(stringResource(R.string.review_card_due, dueLabel), style = MaterialTheme.typography.labelSmall, color = Steady.colors.muted)
            }
            LinkButton(stringResource(R.string.review_edit), { onEdit(card) })
            LinkButton(stringResource(R.string.review_delete), { onDelete(card) })
        }
    }
}

// ---- previews ----

@Composable
private fun ReviewPreviewBody(state: ReviewUiState) =
    ReviewContent(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

@Preview(name = "Review · light", showBackground = true)
@Preview(name = "Review · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReviewPreview() = SteadyTheme {
    ReviewPreviewBody(
        ReviewUiState(
            loading = false, current = PreviewData.card, position = 4, total = 9, revealed = true,
            nextDue = "tomorrow, 4 pm", cards = PreviewData.cards,
            dueLabels = PreviewData.cards.associate { it.id to "now" },
        ),
    )
}

@Preview(name = "Review done · light", showBackground = true)
@Preview(name = "Review done · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ReviewDonePreview() = SteadyTheme {
    ReviewPreviewBody(ReviewUiState(loading = false, position = 9, total = 9, nextDue = "Thu, 4:07 pm", editor = CardDraft()))
}
