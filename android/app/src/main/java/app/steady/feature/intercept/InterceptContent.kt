package app.steady.feature.intercept

import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.steady.R
import app.steady.ui.components.GhostButton
import app.steady.ui.components.Pill
import app.steady.ui.components.SubText
import app.steady.ui.theme.MonoFont
import app.steady.ui.theme.Steady
import app.steady.ui.theme.SteadyTheme
import kotlinx.coroutines.delay

/** Contract (CONTRACTS "Phase 5 — app shell slots"). Phase 7 may add fields with defaults. */
data class InterceptUiState(
    val surfaceId: String,
    val scheduleLabel: String,
    val opensToday: Int,
    val opensBaseline: Int,
    /**
     * Phase 12: false when the running block is not strict. Then every exit is open at once (no breath
     * gate) and the copy is softer.
     */
    val strict: Boolean = true,
)

/**
 * The urge sheet: dimmed backdrop, a 5-second breath, and three exits. "Close <App>" works right away;
 * the swap and "just messages" exits open after the breath ([BreathGate]).
 *
 * Self-contained on purpose: Phase 6 hosts it in an accessibility overlay window, so it only uses
 * [state] and the callbacks (no ViewModel, NavController or back dispatcher).
 */
@Composable
fun InterceptContent(
    state: InterceptUiState,
    onClose: () -> Unit,
    onSwap: () -> Unit,
    onMessagesOnly: () -> Unit,
) {
    InterceptSheet(state, onClose, onSwap, onMessagesOnly, startElapsedMs = 0L)
}

@Composable
internal fun InterceptSheet(
    state: InterceptUiState,
    onClose: () -> Unit,
    onSwap: () -> Unit,
    onMessagesOnly: () -> Unit,
    startElapsedMs: Long,
) {
    val c = Steady.colors
    val copy = remember(state.surfaceId) { InterceptCopy.surface(state.surfaceId) }
    // Non-strict blocks skip the breath: the sheet is a gentle reminder, not a gate.
    val initialElapsed = if (state.strict) startElapsedMs else BreathGate.BREATH_SECONDS * 1000L
    var elapsedMs by remember(state.surfaceId, state.strict) { mutableLongStateOf(initialElapsed) }
    LaunchedEffect(state.surfaceId, state.strict) {
        while (!BreathGate.softExitsEnabled(elapsedMs)) {
            delay(TICK_MS)
            elapsedMs += TICK_MS
        }
    }
    val gates = BreathGate.exits(elapsedMs)
    val secondsLeft = BreathGate.secondsLeft(elapsedMs)

    Box(Modifier.fillMaxSize()) {
        // Dimmed backdrop. Not clickable: dismissing happens through one of the exits.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (c.isDark) 0.65f else 0.5f)))
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(c.screen, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BreathDot(secondsLeft)
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (state.strict) R.string.intercept_breathe else R.string.intercept_breathe_soft),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = c.ink,
                    )
                    val phrase = InterceptCopy.blockPhrase(state.scheduleLabel)
                    SubText(
                        if (phrase != null) stringResource(R.string.intercept_opened_during, copy.name, phrase)
                        else stringResource(R.string.intercept_opened, copy.name),
                    )
                }
            }
            Text(
                stringResource(if (state.strict) R.string.intercept_question else R.string.intercept_question_soft),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                color = c.ink,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CloseAppButton(stringResource(R.string.intercept_close, copy.appName), onClose, enabled = gates.close)
                GhostButton(stringResource(R.string.intercept_swap), onSwap, enabled = gates.swap)
                if (copy.isSection) {
                    val label = if (copy.plural) R.string.intercept_messages_plural else R.string.intercept_messages_singular
                    GhostButton(stringResource(label, copy.name), onMessagesOnly, enabled = gates.messages)
                }
            }
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SubText(stringResource(R.string.intercept_opens_today, copy.name))
                Pill(
                    if (InterceptCopy.showDownFrom(state.opensToday, state.opensBaseline)) {
                        stringResource(R.string.intercept_count_down_from, state.opensToday, state.opensBaseline)
                    } else {
                        state.opensToday.toString()
                    },
                )
            }
        }
    }
}

private const val TICK_MS = 250L

/** 44dp breathing dot with the countdown; scales gently unless the user turned animations off. */
@Composable
private fun BreathDot(secondsLeft: Int) {
    val c = Steady.colors
    val animate = secondsLeft > 0 && !rememberReducedMotion()
    val scale = if (animate) {
        val t = rememberInfiniteTransition(label = "breath")
        t.animateFloat(
            initialValue = 0.9f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(tween(2000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breathScale",
        ).value
    } else 1f
    val description = if (secondsLeft > 0) {
        pluralStringResource(R.plurals.intercept_breath_seconds_left, secondsLeft, secondsLeft)
    } else stringResource(R.string.intercept_breath_done)
    Box(
        Modifier
            .size(44.dp)
            .scale(scale)
            .background(c.urgeSoft, CircleShape)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (secondsLeft > 0) secondsLeft.toString() else stringResource(R.string.intercept_check),
            fontFamily = MonoFont,
            fontSize = 15.sp,
            color = c.urge,
        )
    }
}

/** True when the system "Remove animations" setting is on (animator duration scale 0). */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val inPreview = LocalInspectionMode.current
    return remember(context) {
        !inPreview && runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** Prototype `.close-btn`: the big calm green exit. */
@Composable
private fun CloseAppButton(text: String, onClick: () -> Unit, enabled: Boolean) {
    val c = Steady.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.calm,
            contentColor = if (c.isDark) Color(0xFF06140E) else Color.White,
        ),
        contentPadding = PaddingValues(14.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

// ---- Previews ----

@Preview(name = "Intercept · non-strict · light", showBackground = true, heightDp = 640)
@Composable
private fun InterceptSoftPreview() {
    SteadyTheme(darkTheme = false) {
        InterceptSheet(InterceptUiState("youtube.shorts", "Evening", 2, 6, strict = false), {}, {}, {}, startElapsedMs = 0L)
    }
}

private val previewState = InterceptUiState("instagram.reels", "Coaching", opensToday = 3, opensBaseline = 11)

@Preview(name = "Intercept · breathing · light", showBackground = true, heightDp = 640)
@Composable
private fun InterceptBreathingLightPreview() {
    SteadyTheme(darkTheme = false) { InterceptSheet(previewState, {}, {}, {}, startElapsedMs = 2_000L) }
}

@Preview(name = "Intercept · open · dark", showBackground = true, heightDp = 640, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun InterceptOpenDarkPreview() {
    SteadyTheme(darkTheme = true) { InterceptSheet(previewState, {}, {}, {}, startElapsedMs = 5_000L) }
}

@Preview(name = "Intercept · whole app · light", showBackground = true, heightDp = 640)
@Composable
private fun InterceptAppPreview() {
    SteadyTheme(darkTheme = false) {
        InterceptSheet(InterceptUiState("app:com.pubg.imobile", "", 1, 0), {}, {}, {}, startElapsedMs = 5_000L)
    }
}
