package app.steady.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.steady.ui.theme.Steady

/** Minimum touch target for every interactive element (accessibility). */
val MinTouch = 48.dp

/** Scrollable screen body: safe-area padding, prototype `.screen` background, 18dp gutters. */
@Composable
fun SteadyScreen(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** Prototype `.p-label`: small mono uppercase label. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = Steady.colors.muted,
    )
}

/** Prototype `.p-title`. Marked as a heading for screen readers. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
        color = Steady.colors.ink,
    )
}

@Composable
fun SubText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = Steady.colors.muted)
}

/** Prototype `.opt` button with `aria-pressed`. Use [single] for radio-style groups. */
@Composable
fun OptionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    single: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Steady.colors
    val shape = RoundedCornerShape(12.dp)
    val interaction = if (single) {
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    } else {
        Modifier.toggleable(value = selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
    }
    Box(
        modifier
            .heightIn(min = MinTouch)
            .border(BorderStroke(1.5.dp, if (selected) c.accent else c.line), shape)
            .background(if (selected) c.accentSoft else c.surface, shape)
            .then(interaction)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) c.ink else c.muted,
            textAlign = TextAlign.Center,
        )
    }
}

/** Prototype `.cta`. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = Steady.colors.accent, contentColor = Steady.colors.onAccent),
        contentPadding = PaddingValues(14.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/** Prototype `.ghost`. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = MinTouch),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.5.dp, Steady.colors.line),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Steady.colors.ink),
        contentPadding = PaddingValues(12.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = MinTouch)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = Steady.colors.accent)
    }
}

/** Prototype `.card`. */
@Composable
fun SteadyCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Steady.colors
    val shape = MaterialTheme.shapes.large
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, c.line, shape)
            .background(c.surface, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

enum class PillTone { CALM, URGE, ACCENT }

/** Prototype `.pill`. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, tone: PillTone = PillTone.CALM) {
    val c = Steady.colors
    val (bg, fg) = when (tone) {
        PillTone.CALM -> c.calmSoft to c.calm
        PillTone.URGE -> c.urgeSoft to c.urge
        PillTone.ACCENT -> c.accentSoft to c.accent
    }
    Text(
        text,
        modifier = modifier
            .background(bg, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
    )
}

/**
 * Placeholder body for screens owned by later phases. Shows the screen name and a way back.
 * Replace the whole screen body when implementing the phase.
 */
@Composable
fun StubScreen(name: String, phase: Int, onBack: (() -> Unit)?, extra: @Composable ColumnScope.() -> Unit = {}) {
    SteadyScreen {
        SectionLabel("Phase $phase · coming soon")
        ScreenTitle(name)
        SubText("TODO(phase$phase): this screen is a placeholder from the app shell.")
        extra()
        if (onBack != null) GhostButton("Back", onBack)
    }
}
