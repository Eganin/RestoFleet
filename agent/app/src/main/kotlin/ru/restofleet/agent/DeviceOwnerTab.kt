package ru.restofleet.agent

import androidx.compose.runtime.Composable
import com.arkivanov.essenty.instancekeeper.InstanceKeeper

/**
 * Вкладка Device Owner как подключаемая часть. Привилегированная поверхность (тихая установка APK, киоск, перезагрузка
 * с экрана) существует только в debug-сборке (решение 140): реализацию регистрирует `privilegedModule` из
 * `src/debug`, в release её нет, и `MainActivity` не показывает вкладку.
 */
fun interface DeviceOwnerTab {
    /** Создаёт (или берёт из [keeper], если уже есть) ViewModel вкладки и возвращает её содержимое. */
    fun content(keeper: InstanceKeeper): @Composable () -> Unit
}
