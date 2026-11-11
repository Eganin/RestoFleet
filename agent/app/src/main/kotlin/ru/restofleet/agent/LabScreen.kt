package ru.restofleet.agent

import androidx.compose.runtime.Composable
import com.arkivanov.essenty.instancekeeper.InstanceKeeper
import ru.restofleet.agent.presentation.common.SettingsTarget

/**
 * Capability Lab (с вкладкой Device Owner) как подключаемый экран: отладочный инструмент техника, есть только в
 * debug-сборке (реш. 140, план 3.6). Реализацию регистрирует `privilegedModule` из `src/debug`; в release её нет, и
 * экраны мастера и статуса не показывают кнопку Lab.
 */
fun interface LabScreen {
    /** Создаёт (или берёт из [keeper]) ViewModel экрана и возвращает его содержимое. */
    fun content(keeper: InstanceKeeper, openSettings: (SettingsTarget) -> Unit): @Composable () -> Unit
}
