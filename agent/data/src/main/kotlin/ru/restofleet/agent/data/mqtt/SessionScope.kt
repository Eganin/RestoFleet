package ru.restofleet.agent.data.mqtt

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Процессный scope MQTT-сессии и циклов вокруг неё (решение 175). Без обработчика неожиданное исключение любой задачи
 * в нём роняло бы процесс, а `START_STICKY` перезапускал бы его в цикле падений. Обработчик пишет в лог только класс
 * исключения; `Error` он не глотает, тот идёт дальше к обработчику потока (процесс падает, как и должен).
 */
fun sessionScope(dispatcher: CoroutineDispatcher, log: (String) -> Unit): CoroutineScope =
    CoroutineScope(SupervisorJob() + dispatcher + sessionExceptionHandler(log))

internal fun sessionExceptionHandler(log: (String) -> Unit): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, e ->
        if (e is Error) throw e
        log("session scope: uncaught ${e::class.java.simpleName}")
    }
