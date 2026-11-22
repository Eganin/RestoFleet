package ru.restofleet.console.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.flow.Flow
import ru.restofleet.console.core.designsystem.RfFonts
import ru.restofleet.console.core.designsystem.RfRadius
import ru.restofleet.console.core.designsystem.RfSpacing
import ru.restofleet.console.core.designsystem.RfTheme
import ru.restofleet.console.core.designsystem.StatusTone

private const val THRESHOLD_STROKE = 1.5f
private const val LINE_STROKE = 3f
private const val DOT_RADIUS = 4f
private val THRESHOLD_DASH = floatArrayOf(8f, 6f)

/** Статус-чип: точка и текст на мягком фоне тона. */
@Composable
fun StatusChip(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    val colors = RfTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(RfRadius.S))
            .background(colors.toneSoft(tone))
            .padding(horizontal = RfSpacing.S, vertical = RfSpacing.XS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RfSpacing.XS),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(colors.tone(tone)))
        Text(text, color = colors.tone(tone), fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Бейдж правила `R-03`: моно-шрифт, цвет по важности. */
@Composable
fun RuleBadge(ruleId: String, tone: StatusTone = StatusTone.Off, modifier: Modifier = Modifier) {
    val colors = RfTheme.colors
    Text(
        ruleId,
        modifier = modifier
            .clip(RoundedCornerShape(RfRadius.S))
            .background(colors.toneSoft(tone))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = colors.tone(tone),
        fontFamily = RfFonts.Mono,
        fontSize = 11.sp,
    )
}

/** Плитка метрики; при [alert] выделяется рамкой цвета тревоги (макет FR-CN-05). */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    tone: StatusTone? = null,
    alert: Boolean = false,
) {
    val colors = RfTheme.colors
    val border = if (alert) {
        BorderStroke(2.dp, colors.tone(tone ?: StatusTone.Crit))
    } else {
        BorderStroke(1.dp, colors.line)
    }
    Column(
        modifier
            .clip(RoundedCornerShape(RfRadius.M))
            .background(colors.surface)
            .border(border, RoundedCornerShape(RfRadius.M))
            .padding(RfSpacing.M),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, color = colors.muted, fontSize = 12.sp)
        Text(value, color = tone?.let(colors::tone) ?: colors.fg, style = RfFonts.MonoValue)
        if (caption != null) Text(caption, color = colors.muted, fontSize = 11.sp)
    }
}

/** График одной серии на Canvas с пунктирной линией порога (реш. 421). */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    threshold: Double? = null,
    tone: StatusTone = StatusTone.Ok,
) {
    val colors = RfTheme.colors
    Canvas(modifier.fillMaxWidth().height(96.dp)) {
        val geo = ChartScale.build(points, threshold, size.width, size.height)
        geo.thresholdY?.let {
            drawLine(
                colors.crit, Offset(0f, it), Offset(size.width, it), strokeWidth = THRESHOLD_STROKE,
                pathEffect = PathEffect.dashPathEffect(THRESHOLD_DASH),
            )
        }
        if (geo.line.size >= 2) {
            val path = Path().apply {
                moveTo(geo.line.first().x, geo.line.first().y)
                geo.line.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, colors.tone(tone), style = Stroke(width = LINE_STROKE))
        } else {
            geo.line.firstOrNull()?.let { drawCircle(colors.tone(tone), DOT_RADIUS, Offset(it.x, it.y)) }
        }
    }
}

/** Водопад фаз проверки: сегменты подряд, ширина пропорциональна времени. */
@Composable
fun PhaseWaterfall(phases: List<PhaseSegment>, modifier: Modifier = Modifier) {
    val colors = RfTheme.colors
    val palette = listOf(colors.accent, colors.ok, colors.warn, colors.crit)
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        PhaseLayout.build(phases, size.width).forEachIndexed { i, bar ->
            drawRect(
                palette[i % palette.size],
                topLeft = Offset(bar.offset, 0f),
                size = androidx.compose.ui.geometry.Size(bar.width, size.height),
            )
        }
    }
}

@Composable
fun EmptyState(title: String, modifier: Modifier = Modifier, hint: String? = null) {
    val colors = RfTheme.colors
    Column(
        modifier.fillMaxSize().padding(RfSpacing.XL),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = colors.fg, fontWeight = FontWeight.Medium)
        if (hint != null) {
            Spacer(Modifier.height(RfSpacing.XS))
            Text(hint, color = colors.muted, fontSize = 13.sp)
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
    retryLabel: String = "Повторить",
) {
    val colors = RfTheme.colors
    Column(
        modifier.fillMaxSize().padding(RfSpacing.XL),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = colors.crit, fontWeight = FontWeight.Medium)
        if (onRetry != null) TextButton(onClick = onRetry) { Text(retryLabel) }
    }
}

/** Строка со свайпом: вправо — [rightLabel], влево — [leftLabel]; действие выполняет вызывающий. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeActionRow(
    rightLabel: String,
    leftLabel: String,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = RfTheme.colors
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            when (it) {
                SwipeToDismissBoxValue.StartToEnd -> onSwipeRight()
                SwipeToDismissBoxValue.EndToStart -> onSwipeLeft()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().background(if (toRight) colors.okSoft else colors.critSoft).padding(RfSpacing.L),
                contentAlignment = if (toRight) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Text(if (toRight) rightLabel else leftLabel, color = if (toRight) colors.ok else colors.crit)
            }
        },
        content = { content() },
    )
}

/** Единственный подписчик эффектов ViewModel (канон MVI, разд. 5 архитектуры). */
@Composable
fun <E> ObserveEffects(effects: Flow<E>, onEffect: suspend (E) -> Unit) {
    LaunchedEffect(effects) { effects.collect { onEffect(it) } }
}
