package ru.restofleet.console.root

import com.arkivanov.decompose.ComponentContext
import ru.restofleet.console.core.decompose.ContentComponent

/** Вкладки нижней панели. */
enum class Tab(val title: String, val glyph: String) {
    Overview("Обзор", "◉"),
    Devices("Устройства", "▤"),
    Alerts("Тревоги", "▲"),
    Restaurants("Рестораны", "⌂"),
}

/** Экраны внутри стеков вкладок. Конфигурации — только данные: ни фич, ни компонентов. */
sealed interface Screen {
    data object Dashboard : Screen

    /** Список устройств; [restaurantId] — из списка ресторанов, [problemsOnly] — стартовое значение фильтра. */
    data class Devices(
        val restaurantId: String? = null,
        val restaurantName: String? = null,
        val problemsOnly: Boolean = true,
    ) : Screen

    data class DeviceDetails(val deviceId: String) : Screen

    /** Тревоги и инциденты под одним переключателем. */
    data object Alerts : Screen

    data class IncidentDetails(val incidentId: String) : Screen

    data object Restaurants : Screen

    data object Provisioning : Screen
}

/** Корневой экран вкладки. */
fun Tab.rootScreen(): Screen = when (this) {
    Tab.Overview -> Screen.Dashboard
    Tab.Devices -> Screen.Devices(problemsOnly = true)
    Tab.Alerts -> Screen.Alerts
    Tab.Restaurants -> Screen.Restaurants
}

/** Навигация, доступная экранам через их `Output`: фичи друг о друге не знают, связывает родитель. */
interface MainNavigator {
    /** Открывает экран поверх стека активной вкладки. */
    fun open(screen: Screen)

    fun switchTab(tab: Tab)

    fun logout()
}

/** Создаёт компонент экрана. Реализация (composition root) знает фабрики фич и `Output`-ы. */
fun interface ScreenFactory {
    fun create(context: ComponentContext, screen: Screen, navigator: MainNavigator): ContentComponent
}
