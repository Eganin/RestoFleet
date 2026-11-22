package ru.restofleet.console.root

import androidx.compose.runtime.Composable
import ru.restofleet.console.core.decompose.ContentComponent
import ru.restofleet.console.core.ui.EmptyState

/** Экран-заглушка для разделов, чья фича ещё не подключена. */
class PlaceholderComponent(private val title: String) : ContentComponent {
    @Composable
    override fun Content() {
        EmptyState(title = title, hint = "Раздел в разработке")
    }
}
