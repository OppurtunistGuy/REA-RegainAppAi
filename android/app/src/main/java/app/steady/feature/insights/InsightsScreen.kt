package app.steady.feature.insights

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.steady.R
import app.steady.data.CardRepository
import app.steady.data.SessionRepository
import app.steady.di.AppContainer
import app.steady.feature.learning.Minutes
import app.steady.learning.Card
import app.steady.learning.FocusSession
import app.steady.learning.Scheduler
import app.steady.nav.Navigator
import app.steady.nav.Review
import app.steady.ui.components.GhostButton
import app.steady.ui.components.PrimaryButton
import app.steady.ui.components.ScreenTitle
import app.steady.ui.components.SectionLabel
import app.steady.ui.components.SteadyCard
import app.steady.ui.components.SteadyScreen
import app.steady.ui.components.SubText
import app.steady.ui.theme.MonoFont
import app.steady.ui.theme.Steady
import app.steady.ui.theme.SteadyTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Last four weeks, aggregate counts only. [surfaceCounts] comes from
 * `SessionRepository.interceptsBySurface` over the same days (see [InsightsScreen]).
 */
class InsightsViewModel(
    sessionRepo: SessionRepository,
    cardRepo: CardRepository,
    clock: Clock,
    private val zone: ZoneId,
    surfaceCounts: Flow<Map<String, Int>> = flowOf(emptyMap()),
) : ViewModel() {
    private val now = clock.instant()
    private val days: List<LocalDate> = InsightsLogic.days(now.atZone(zone).toLocalDate())

    private val interceptsByDay: Flow<Map<LocalDate, Int>> =
        combine(days.map { sessionRepo.interceptsOn(it) }) { counts -> days.zip(counts.toList()).toMap() }

    val state: StateFlow<InsightsUiState> =
        combine(sessionRepo.sessions, interceptsByDay, surfaceCounts, cardRepo.cards) { s, i, sc, c ->
            InsightsLogic.build(s, i, sc, c, now, zone)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())
}

@Composable
fun InsightsScreen(container: AppContainer, nav: Navigator) {
    val vm: InsightsViewModel = viewModel {
        val days = InsightsLogic.days(container.clock.instant().atZone(container.zone).toLocalDate())
        InsightsViewModel(
            container.sessionRepo, container.cardRepo, container.clock, container.zone,
            surfaceCounts = container.sessionRepo.interceptsBySurface(days.first(), days.last().plusDays(1)),
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    InsightsContent(state, onReview = { nav.go(Review) }, onBack = nav::back)
}

@Composable
fun InsightsContent(state: InsightsUiState, onReview: () -> Unit, onBack: () -> Unit) {
    val c = Steady.colors
    SteadyScreen {
        SectionLabel(stringResource(R.string.insights_label))
        ScreenTitle(stringResource(R.string.insights_title))
        if (!state.loading) SummaryText(state.summary)

        val thisWeek = stringResource(R.string.insights_this_week)
        val labels = state.weeks.map { if (it.isCurrent) thisWeek else it.label }
        SteadyCard {
            SectionLabel(stringResource(R.string.insights_focus))
            BarChart(
                values = state.weeks.map { it.focusMinutes },
                labels = labels,
                valueText = { Minutes.short(it) },
                highlightLast = true,
                color = c.accent,
            )
        }
        SteadyCard {
            SectionLabel(stringResource(R.string.insights_intercepts))
            BarChart(
                values = state.weeks.map { it.intercepts },
                labels = labels,
                valueText = { it.toString() },
                highlightLast = true,
                color = c.calm,
            )
        }
        SteadyCard {
            SectionLabel(stringResource(R.string.insights_surfaces))
            if (state.topSurfaces.isEmpty()) {
                SubText(stringResource(R.string.insights_surfaces_empty))
            } else {
                state.topSurfaces.forEach { s ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(s.label, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                        Text(s.count.toString(), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont), color = c.ink)
                    }
                }
            }
        }
        SteadyCard {
            SectionLabel(stringResource(R.string.insights_due))
            if (state.dueNext7 == 0) {
                SubText(stringResource(R.string.insights_due_none))
            } else {
                Text(
                    pluralStringResource(R.plurals.insights_due_total, state.dueNext7, state.dueNext7),
                    style = MaterialTheme.typography.titleMedium,
                    color = c.ink,
                )
                BarChart(
                    values = state.dueDays.map { it.count },
                    labels = state.dueDays.map { it.label },
                    valueText = { it.toString() },
                    highlightLast = false,
                    color = c.accent,
                    height = 72,
                )
            }
        }
        if ((state.dueDays.firstOrNull()?.count ?: 0) > 0) PrimaryButton(stringResource(R.string.insights_review_now), onReview)
        GhostButton(stringResource(R.string.learning_back), onBack)
    }
}

@Composable
private fun SummaryText(summary: InsightsSummary) {
    val text = if (!summary.anyData) {
        stringResource(R.string.insights_summary_empty)
    } else {
        val base = pluralStringResource(
            R.plurals.insights_summary_week, summary.thisWeekIntercepts,
            Minutes.long(summary.thisWeekMinutes), summary.thisWeekIntercepts,
        )
        val trend = when (summary.trend) {
            Trend.UP -> stringResource(R.string.insights_trend_up)
            Trend.SAME -> stringResource(R.string.insights_trend_same)
            Trend.DOWN -> stringResource(R.string.insights_trend_down)
            Trend.NONE -> null
        }
        listOfNotNull(base, trend).joinToString(" ")
    }
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Steady.colors.ink)
}

/**
 * Minimal labelled bar chart drawn on a Canvas. Values above, labels below; the last bar (this week)
 * is full colour, earlier ones are lighter. Announced to screen readers as "label: value" pairs.
 */
@Composable
fun BarChart(
    values: List<Int>,
    labels: List<String>,
    valueText: (Int) -> String,
    highlightLast: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    height: Int = 120,
) {
    val c = Steady.colors
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val pairs = labels.zip(values)
    val desc = pairs.joinToString(", ") { (l, v) -> "$l: ${valueText(v)}" }
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = desc }) {
        Row(Modifier.fillMaxWidth()) {
            values.forEach { v ->
                Text(
                    valueText(v),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.ink,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = slot * 0.56f
            values.forEachIndexed { i, v ->
                val h = if (v <= 0) 2.dp.toPx() else (v / max.toFloat()) * size.height
                val isLast = i == values.lastIndex
                drawRoundRect(
                    color = if (!highlightLast || isLast) color else color.copy(alpha = 0.45f),
                    topLeft = Offset(slot * i + (slot - barW) / 2f, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
            }
            drawLine(c.line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            labels.forEach { l ->
                Text(l, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = c.muted, textAlign = TextAlign.Center)
            }
        }
    }
}

// ---- previews ----

private fun previewState(): InsightsUiState {
    val zone = ZoneId.of("Asia/Kolkata")
    val now = java.time.Instant.parse("2026-11-26T11:12:00Z")
    val sessions = (0L..20L).map { d ->
        val start = now.minusSeconds(d * 86_400 + 3_600)
        FocusSession("s$d", null, start, start.plusSeconds(((d % 4) + 1) * 900), 1, 0)
    }
    val today = now.atZone(zone).toLocalDate()
    val intercepts = InsightsLogic.days(today).associateWith { (it.dayOfMonth % 3) }
    val cards = (0L..5L).map { i ->
        Card("c$i", "physics", "Q$i", "A$i", now, Scheduler.newState(now.plusSeconds(i * 86_400)))
    }
    return InsightsLogic.build(sessions, intercepts, mapOf("instagram.reels" to 9, "youtube.shorts" to 4), cards, now, zone)
}

@Preview(name = "Insights · light", showBackground = true)
@Preview(name = "Insights · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun InsightsPreview() = SteadyTheme { InsightsContent(previewState(), {}, {}) }

@Preview(name = "Insights empty · light", showBackground = true)
@Preview(name = "Insights empty · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun InsightsEmptyPreview() = SteadyTheme {
    val zone = ZoneId.of("Asia/Kolkata")
    InsightsContent(InsightsLogic.build(emptyList(), emptyMap(), emptyMap(), emptyList(), java.time.Instant.EPOCH, zone), {}, {})
}
