package app.steady.feature.learning

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.steady.R
import app.steady.learning.Card
import app.steady.learning.Rating
import app.steady.ui.components.GhostButton
import app.steady.ui.components.OptionChip
import app.steady.ui.components.PrimaryButton
import app.steady.ui.components.SectionLabel
import app.steady.ui.components.SteadyCard
import app.steady.ui.theme.MonoFont
import app.steady.ui.theme.Steady

/** Prototype `.timerbar`: 6dp track, accent fill shrinking with [fraction]. */
@Composable
fun TimerBar(fraction: Float, description: String, modifier: Modifier = Modifier) {
    val c = Steady.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(c.line)
            .semantics { contentDescription = description },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(c.accent),
        )
    }
}

/** Prototype `.flash`: question, answer when revealed, and the Show answer button. */
@Composable
fun Flashcard(card: Card, revealed: Boolean, onReveal: () -> Unit, modifier: Modifier = Modifier) {
    val c = Steady.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp)
            .border(1.dp, c.line, shape)
            .background(c.surface, shape)
            .padding(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val deck = Decks.displayName(card.deckId)
        SectionLabel(
            if (deck != null) stringResource(R.string.learning_from_notes, deck.replaceFirstChar { it.uppercase() })
            else stringResource(R.string.learning_from_notes_plain),
        )
        Text(
            card.front,
            style = MaterialTheme.typography.titleLarge,
            color = c.ink,
            textAlign = TextAlign.Center,
        )
        if (revealed) {
            Text(
                card.back,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = MonoFont, fontSize = 21.sp),
                color = c.accent,
                textAlign = TextAlign.Center,
            )
        } else {
            GhostButton(stringResource(R.string.learning_show_answer), onReveal)
        }
    }
}

@Composable
fun ratingLabel(r: Rating): String = when (r) {
    Rating.AGAIN -> stringResource(R.string.rate_again)
    Rating.HARD -> stringResource(R.string.rate_hard)
    Rating.GOOD -> stringResource(R.string.rate_good)
}

/** Prototype `.rate`: Again / Hard / Got it. */
@Composable
fun RatingRow(selected: Rating?, enabled: Boolean, onRate: (Rating) -> Unit, modifier: Modifier = Modifier) {
    val groupLabel = stringResource(R.string.learning_rating_group)
    Row(
        modifier.fillMaxWidth().semantics { contentDescription = groupLabel },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RatingOrder.forEach { r ->
            OptionChip(
                text = ratingLabel(r),
                selected = selected == r,
                onClick = { onRate(r) },
                single = true,
                enabled = enabled || selected == r,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Front/back form used by Swap (empty state) and Review (add/edit). */
@Composable
fun CardEditorForm(
    title: String,
    draft: CardDraft,
    onFront: (String) -> Unit,
    onBack: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SteadyCard(modifier) {
        SectionLabel(title)
        OutlinedTextField(
            value = draft.front,
            onValueChange = onFront,
            label = { Text(stringResource(R.string.learning_card_front)) },
            placeholder = { Text(stringResource(R.string.learning_card_front_hint)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.back,
            onValueChange = onBack,
            label = { Text(stringResource(R.string.learning_card_back)) },
            placeholder = { Text(stringResource(R.string.learning_card_back_hint)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(stringResource(R.string.learning_save_card), onSave, enabled = draft.canSave)
        GhostButton(stringResource(R.string.learning_cancel), onCancel)
    }
}
