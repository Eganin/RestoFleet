package ru.restofleet.console.root

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import androidx.compose.ui.Modifier
import ru.restofleet.console.core.decompose.ContentComponent
import ru.restofleet.console.core.designsystem.RfSpacing

/** Вкладка «Тревоги»: переключатель «Тревоги | Инциденты» в шапке (FR-CN-10); сами экраны — компоненты фич. */
class AlertsHostComponent(
    private val alerts: ContentComponent,
    private val incidents: ContentComponent,
) : ContentComponent {
    enum class Section(val title: String) { Alerts("Тревоги"), Incidents("Инциденты") }

    private val mutableSection = MutableValue(Section.Alerts)
    val section: Value<Section> = mutableSection

    fun select(section: Section) {
        mutableSection.value = section
    }

    @Composable
    override fun Content() {
        val current by section.subscribeAsState()
        Column {
            Row(Modifier.padding(horizontal = RfSpacing.L, vertical = RfSpacing.S)) {
                Section.entries.forEach {
                    FilterChip(
                        selected = it == current,
                        onClick = { select(it) },
                        label = { Text(it.title) },
                        modifier = Modifier.padding(end = RfSpacing.S),
                    )
                }
            }
            when (current) {
                Section.Alerts -> alerts.Content()
                Section.Incidents -> incidents.Content()
            }
        }
    }
}
