package ru.restofleet.console.core.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.console.core.designsystem.RfTheme
import ru.restofleet.console.core.designsystem.StatusTone

class UiGeometryTest {
    @Test
    fun `chart scale maps points and includes threshold in range`() {
        val geo = ChartScale.build(
            listOf(ChartPoint(0, 30.0), ChartPoint(10, 50.0), ChartPoint(20, 40.0)),
            threshold = 45.0, width = 200f, height = 100f,
        )
        assertThat(geo.line.map { it.x }).containsExactly(0f, 100f, 200f).inOrder()
        assertThat(geo.minY).isLessThan(30.0)
        assertThat(geo.maxY).isGreaterThan(50.0)
        // выше значение — меньше y; порог 45 лежит между 40 и 50
        assertThat(geo.line[1].y).isLessThan(geo.thresholdY!!)
        assertThat(geo.thresholdY!!).isLessThan(geo.line[2].y)
    }

    @Test
    fun `chart scale handles empty, single and flat series`() {
        assertThat(ChartScale.build(emptyList(), null, 100f, 100f).line).isEmpty()
        val single = ChartScale.build(listOf(ChartPoint(5, 7.0)), null, 100f, 100f)
        assertThat(single.line.single().x).isEqualTo(50f)
        assertThat(single.line.single().y).isEqualTo(50f)
        val flat = ChartScale.build(listOf(ChartPoint(0, 3.0), ChartPoint(1, 3.0)), null, 100f, 100f)
        assertThat(flat.line.map { it.y }.distinct()).containsExactly(50f)
        assertThat(ChartScale.build(emptyList(), 45.0, 10f, 10f).thresholdY).isNotNull()
    }

    @Test
    fun `chart scale sorts points by time`() {
        val geo = ChartScale.build(listOf(ChartPoint(10, 1.0), ChartPoint(0, 2.0)), null, 100f, 100f)
        assertThat(geo.line.map { it.x }).containsExactly(0f, 100f).inOrder()
    }

    @Test
    fun `waterfall lays phases one after another and skips zero ones`() {
        val bars = PhaseLayout.build(
            listOf(PhaseSegment("dns", 10), PhaseSegment("tcp", 0), PhaseSegment("tls", 30), PhaseSegment("ttfb", 60)),
            100f,
        )
        assertThat(bars.map { it.name }).containsExactly("dns", "tls", "ttfb").inOrder()
        assertThat(bars.map { it.offset }).containsExactly(0f, 10f, 40f).inOrder()
        assertThat(bars.sumOf { it.width.toDouble() }).isWithin(0.001).of(100.0)
        assertThat(PhaseLayout.build(emptyList(), 100f)).isEmpty()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `components render and react`() = runComposeUiTest {
        var retried = false
        setContent {
            RfTheme(dark = false) {
                androidx.compose.foundation.layout.Column {
                    StatusChip("online", StatusTone.Ok)
                    RuleBadge("R-03", StatusTone.Crit)
                    MetricTile("Заряд", "34 %", caption = "от сети", alert = true, tone = StatusTone.Crit)
                    ErrorState("Нет связи", onRetry = { retried = true })
                    RfTabs(listOf("Один", "Два"), 0, {})
                }
            }
        }
        onNodeWithText("online").assertIsDisplayed()
        onNodeWithText("R-03").assertIsDisplayed()
        onNodeWithText("34 %").assertIsDisplayed()
        onNodeWithText("Повторить").performClick()
        assertThat(retried).isTrue()
    }
}
