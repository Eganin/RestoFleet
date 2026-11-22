package ru.restofleet.console.root.di

import org.koin.core.scope.Scope
import ru.restofleet.console.root.PlaceholderComponent
import ru.restofleet.console.root.Screen
import ru.restofleet.console.root.ScreenFactory

/** Связывает `Screen` с фабриками фич и их `Output`-ами. Здесь допустим `get()` (composition root). */
internal fun Scope.screenFactory(): ScreenFactory = ScreenFactory { _, screen, _ ->
    PlaceholderComponent(
        when (screen) {
            Screen.Dashboard -> "Обзор"
            is Screen.Devices -> "Устройства"
            is Screen.DeviceDetails -> "Устройство"
            Screen.Alerts -> "Тревоги"
            is Screen.IncidentDetails -> "Инцидент"
            Screen.Restaurants -> "Рестораны"
            Screen.Provisioning -> "Новое устройство"
        },
    )
}
