package ru.restofleet.console.core.testing

import com.arkivanov.decompose.DecomposeSettings

/** Тесты компонентов идут не в главном потоке: проверку потока Decompose отключаем. */
object DecomposeTestSupport {
    fun disableMainThreadCheck() {
        DecomposeSettings.update { it.copy(mainThreadCheckEnabled = false) }
    }
}
