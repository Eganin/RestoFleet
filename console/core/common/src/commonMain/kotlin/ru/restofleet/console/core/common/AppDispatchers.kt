package ru.restofleet.console.core.common

import kotlinx.coroutines.CoroutineDispatcher

/** Диспетчеры приложения: подменяются в тестах. Собираются в composition root. */
class AppDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
)
