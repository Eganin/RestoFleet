package ru.restofleet.console.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RfThemeTest {
    @Test
    fun `palettes map statuses to tokens`() {
        StatusTone.entries.forEach {
            assertThat(RfColors.Light.tone(it)).isNotEqualTo(RfColors.Light.toneSoft(it))
        }
        assertThat(RfColors.Light.tone(StatusTone.Crit)).isEqualTo(RfColors.Light.crit)
        assertThat(RfColors.Dark.toneSoft(StatusTone.Ok)).isEqualTo(RfColors.Dark.okSoft)
        assertThat(RfColors.of(true)).isSameInstanceAs(RfColors.Dark)
        assertThat(RfColors.of(false).dark).isFalse()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `theme provides tokens and material scheme`() = runComposeUiTest {
        var seen: RfColors? = null
        var primaryMatches = false
        setContent {
            RfTheme(dark = true) {
                seen = RfTheme.colors
                primaryMatches = MaterialTheme.colorScheme.primary == RfColors.Dark.accent
            }
        }
        assertThat(seen).isEqualTo(RfColors.Dark)
        assertThat(primaryMatches).isTrue()
    }
}
