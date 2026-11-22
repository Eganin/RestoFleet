package ru.restofleet.console.core.decompose

import androidx.compose.runtime.Composable

/**
 * Компонент, умеющий нарисовать себя. Родитель получает такие компоненты фабриками (вкладки «Тень»
 * и «Команда» карточки устройства), не зная о фиче, которая их поставляет.
 */
interface ContentComponent {
    @Composable
    fun Content()
}
