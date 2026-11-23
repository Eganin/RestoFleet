package ru.restofleet.console.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.restofleet.console.core.designsystem.RfSpacing
import ru.restofleet.console.core.designsystem.RfTheme

/**
 * Горизонтальные вкладки с подчёркиванием выбранной. Ширина вкладки — по заголовку (`IntrinsicSize.Max`): раньше
 * подчёркивание с `fillMaxWidth` растягивало первую вкладку на всю строку, остальные сжимались в ноль (V-3). Не
 * поместившиеся вкладки прокручиваются.
 */
@Composable
fun RfTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = RfTheme.colors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(RfSpacing.XS),
    ) {
        titles.forEachIndexed { i, title ->
            Column(
                Modifier.width(IntrinsicSize.Max).clickable { onSelect(i) }
                    .padding(horizontal = RfSpacing.M, vertical = RfSpacing.S),
            ) {
                Text(
                    title,
                    color = if (i == selected) colors.accent else colors.muted,
                    fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 14.sp,
                )
                Row(
                    Modifier.height(2.dp).fillMaxWidth().background(if (i == selected) colors.accent else colors.bg),
                ) {}
            }
        }
    }
}
