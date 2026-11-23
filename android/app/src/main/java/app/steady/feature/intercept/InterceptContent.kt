package app.steady.feature.intercept

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.steady.ui.components.GhostButton
import app.steady.ui.components.Pill
import app.steady.ui.components.PrimaryButton
import app.steady.ui.components.ScreenTitle
import app.steady.ui.components.SubText
import app.steady.ui.theme.Steady

/** Contract (CONTRACTS "Phase 5 — app shell slots"). Phase 7 may add fields with defaults. */
data class InterceptUiState(
    val surfaceId: String,
    val scheduleLabel: String,
    val opensToday: Int,
    val opensBaseline: Int,
)

/**
 * TODO(phase7): replace this body with the real breath + three exits sheet (breath countdown gates
 * the swap and messages exits). Hosted both in-app ([InterceptScreen]) and in the Phase 6 overlay.
 */
@Composable
fun InterceptContent(
    state: InterceptUiState,
    onClose: () -> Unit,
    onSwap: () -> Unit,
    onMessagesOnly: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Breathe once.", style = MaterialTheme.typography.titleMedium, color = Steady.colors.ink)
            SubText("You opened ${surfaceName(state.surfaceId)} during ${state.scheduleLabel}.")
            ScreenTitle("What did you come here for?")
            PrimaryButton("Close the app", onClose)
            GhostButton("60-second swap: a flashcard", onSwap)
            GhostButton("Just messages (${surfaceName(state.surfaceId)} stays hidden)", onMessagesOnly)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SubText("Opens today")
                Pill("${state.opensToday} · usual ${state.opensBaseline}")
            }
        }
    }
}

internal fun surfaceName(surfaceId: String): String = when (surfaceId) {
    "instagram.reels" -> "Reels"
    "youtube.shorts" -> "Shorts"
    "snapchat.spotlight" -> "Spotlight"
    "facebook.reels" -> "Facebook Reels"
    else -> surfaceId.substringAfter(':')
}
