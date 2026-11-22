package ru.restofleet.console.feature.commands.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

/** Режим устройства и история команд — один запрос экрана. */
data class CommandsOverview(val mode: String?, val specs: List<CommandSpec>, val history: List<CommandRecord>)

class LoadCommandsOverview(private val repository: CommandsRepository, private val available: AvailableCommands) {
    suspend operator fun invoke(deviceId: String): Outcome<CommandsOverview, ConsoleError> =
        when (val mode = repository.mode(deviceId)) {
            is Outcome.Failure -> mode
            is Outcome.Success -> when (val history = repository.history(deviceId, HISTORY_LIMIT)) {
                is Outcome.Failure -> history
                is Outcome.Success -> Outcome.Success(CommandsOverview(mode.value, available(mode.value), history.value))
            }
        }

    companion object {
        const val HISTORY_LIMIT = 20
    }
}

/** Отправка. Обязательные параметры проверяются до запроса, пустые значения не отправляются. */
class SendCommand(private val repository: CommandsRepository) {
    suspend operator fun invoke(deviceId: String, type: CommandType, params: Map<String, String>): Outcome<CommandRecord, ConsoleError> {
        val clean = params.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        val missing = type.params.firstOrNull { it !in clean }
        return if (missing != null) {
            Outcome.Failure(ConsoleError.InvalidInput(missing, "empty"))
        } else {
            repository.send(deviceId, type, clean.filterKeys { it in type.params })
        }
    }
}

/**
 * Опрос статуса раз в 2 с до итогового состояния или 60 с (FR-CN-07). Каждое наблюдение отдаётся потоком;
 * ошибка одного запроса не прерывает опрос — он продолжается до таймаута.
 */
class TrackCommand(private val repository: CommandsRepository, private val clock: Clock) {
    operator fun invoke(cmdId: String): Flow<CommandRecord> = flow {
        val deadline = clock.nowMillis() + TIMEOUT_MS
        while (true) {
            val record = (repository.status(cmdId) as? Outcome.Success)?.value
            if (record != null) emit(record)
            if (record?.state?.final == true || clock.nowMillis() >= deadline) break
            delay(INTERVAL_MS)
        }
    }

    companion object {
        const val INTERVAL_MS = 2_000L
        const val TIMEOUT_MS = 60_000L
    }
}

class RefreshHistory(private val repository: CommandsRepository) {
    suspend operator fun invoke(deviceId: String): Outcome<List<CommandRecord>, ConsoleError> =
        repository.history(deviceId, LoadCommandsOverview.HISTORY_LIMIT)
}
