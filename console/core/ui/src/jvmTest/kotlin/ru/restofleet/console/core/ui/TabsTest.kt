package ru.restofleet.console.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.console.core.designsystem.RfTheme

/** V-3: каждая вкладка `RfTabs` получает ширину по своему заголовку, вкладки не налезают друг на друга. */
@OptIn(ExperimentalTestApi::class)
class TabsTest {
    private val titles = listOf("Состояние", "Проверки", "События", "Тень")

    @Test
    fun `every tab is as wide as its title and tabs follow one another`() = runComposeUiTest {
        var selected = 0
        setContent {
            RfTheme(dark = false) {
                Box(Modifier.width(360.dp)) { RfTabs(titles, selected, { selected = it }) }
            }
        }

        val bounds = titles.map { onNodeWithText(it).getUnclippedBoundsInRoot() }
        bounds.forEach { assertThat((it.right - it.left).value).isAtLeast(MIN_TAB_DP) }
        bounds.zipWithNext().forEach { (a, b) -> assertThat(b.left.value).isAtLeast(a.right.value) }
        onNodeWithText("Тень").performScrollTo().performClick()
        assertThat(selected).isEqualTo(3)
    }

    private companion object {
        const val MIN_TAB_DP = 30f
    }
}
