package ru.restofleet.console.root

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import ru.restofleet.console.core.designsystem.RfTheme

/** Общий корень интерфейса для Android и Desktop. */
@Composable
fun RootContent(component: RootComponent, modifier: Modifier = Modifier, dark: Boolean? = null) {
    val content: @Composable () -> Unit = {
        Box(modifier.fillMaxSize().background(RfTheme.colors.bg)) {
            Children(stack = component.stack, animation = stackAnimation(fade())) {
                when (val child = it.instance) {
                    RootComponent.Child.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    is RootComponent.Child.Login -> child.component.Content()
                    is RootComponent.Child.Main -> MainContent(child.component)
                }
            }
        }
    }
    if (dark == null) RfTheme(content = content) else RfTheme(dark = dark, content = content)
}

@Composable
fun MainContent(component: MainComponent, modifier: Modifier = Modifier) {
    val active by component.activeTab.subscribeAsState()
    Scaffold(
        modifier = modifier,
        containerColor = RfTheme.colors.bg,
        bottomBar = {
            NavigationBar(containerColor = RfTheme.colors.surface) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == active,
                        onClick = { component.select(tab) },
                        icon = { Text(tab.glyph) },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { padding ->
        val stack by component.stack(active).subscribeAsState()
        Box(Modifier.padding(padding).fillMaxSize()) {
            Children(stack = stack, animation = stackAnimation(fade())) { it.instance.component.Content() }
        }
    }
}
