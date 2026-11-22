package ru.restofleet.console.core.testing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import ru.restofleet.console.core.common.AppDispatchers

/** Все три диспетчера — один тестовый: порядок выполнения детерминирован. */
object TestDispatchers {
    fun of(dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()): AppDispatchers =
        AppDispatchers(main = dispatcher, io = dispatcher, default = dispatcher)
}
