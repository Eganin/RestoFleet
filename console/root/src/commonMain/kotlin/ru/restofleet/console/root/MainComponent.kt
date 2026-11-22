package ru.restofleet.console.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.backhandler.BackCallback
import com.arkivanov.essenty.lifecycle.doOnDestroy
import ru.restofleet.console.core.decompose.ContentComponent

/**
 * Главный экран: нижняя панель и по отдельному стеку на вкладку (состояние вкладки не теряется при переключении).
 * Realtime запускается на время жизни главного экрана и останавливается при выходе.
 */
class MainComponent(
    componentContext: ComponentContext,
    private val screens: ScreenFactory,
    private val onLogout: () -> Unit,
    startRealtime: () -> Unit = {},
    stopRealtime: () -> Unit = {},
) : ComponentContext by componentContext {
    class Child(val screen: Screen, val component: ContentComponent)

    private val navigator = object : MainNavigator {
        override fun open(screen: Screen) = this@MainComponent.open(screen)

        override fun switchTab(tab: Tab) = this@MainComponent.select(tab)

        override fun back() {
            this@MainComponent.back()
        }

        override fun logout() = onLogout()
    }

    private val navigation: Map<Tab, StackNavigation<Screen>> = Tab.entries.associateWith { StackNavigation() }

    private val stacks: Map<Tab, Value<ChildStack<Screen, Child>>> = Tab.entries.associateWith { tab ->
        childStack(
            source = navigation.getValue(tab),
            serializer = null,
            initialConfiguration = tab.rootScreen(),
            key = "tab_${tab.name}",
            handleBackButton = false,
        ) { screen, context -> Child(screen, screens.create(context, screen, navigator)) }
    }

    private val mutableActiveTab = MutableValue(Tab.Overview)
    val activeTab: Value<Tab> = mutableActiveTab

    private val backCallback = BackCallback(isEnabled = false) { back() }

    init {
        backHandler.register(backCallback)
        startRealtime()
        lifecycle.doOnDestroy(stopRealtime)
    }

    fun stack(tab: Tab): Value<ChildStack<Screen, Child>> = stacks.getValue(tab)

    fun select(tab: Tab) {
        mutableActiveTab.value = tab
        syncBack()
    }

    fun open(screen: Screen) {
        navigation.getValue(mutableActiveTab.value).push(screen)
        syncBack()
    }

    /** Назад: внутри стека вкладки, затем на «Обзор»; `false` — дальше некуда (закроет система). */
    fun back(): Boolean {
        val tab = mutableActiveTab.value
        val handled = when {
            stacks.getValue(tab).value.backStack.isNotEmpty() -> {
                navigation.getValue(tab).pop()
                true
            }
            tab != Tab.Overview -> {
                mutableActiveTab.value = Tab.Overview
                true
            }
            else -> false
        }
        syncBack()
        return handled
    }

    private fun syncBack() {
        val tab = mutableActiveTab.value
        backCallback.isEnabled = tab != Tab.Overview || stacks.getValue(tab).value.backStack.isNotEmpty()
    }
}
