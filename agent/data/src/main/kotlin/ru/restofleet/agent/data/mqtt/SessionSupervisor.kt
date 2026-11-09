package ru.restofleet.agent.data.mqtt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.restofleet.agent.domain.port.BrokerSession
import ru.restofleet.agent.domain.port.Outbox
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.agent.domain.usecase.ApplyDesiredShadow
import ru.restofleet.agent.domain.usecase.FlushOutbox
import ru.restofleet.agent.domain.usecase.HandleIncomingCommand
import kotlin.coroutines.cancellation.CancellationException

/**
 * Циклы вокруг сессии, каждый изолирован (канон 78: сбой одного не роняет остальные и саму сессию, `Error` не
 * ловится):
 * - сессия ([BrokerSession.connect]);
 * - команды: `HandleIncomingCommand`, затем досылка outbox, если он не пуст и связь есть (итог `reboot` лежит только
 *   в outbox, решение 142), и лишь после этого поток подтверждает сообщение брокеру;
 * - `desired`: `ApplyDesiredShadow`, ack после возврата;
 * - досылка при каждом новом подключении ([connectedEpoch], уже после `online` и подписок) и раз в
 *   [flushIntervalMs], пока есть связь и outbox не пуст.
 * Команды исполняются строго последовательно: один коллектор.
 */
class SessionSupervisor internal constructor(
    private val scope: CoroutineScope,
    private val session: BrokerSession,
    private val connectedEpoch: StateFlow<Int>,
    private val handleCommand: HandleIncomingCommand,
    private val applyDesired: ApplyDesiredShadow,
    private val flush: FlushOutbox,
    private val outbox: Outbox,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
    private val log: (String) -> Unit = {},
) {
    private var jobs: List<Job> = emptyList()

    /** Запускает циклы; повторный вызов при работающих циклах ничего не делает. */
    @Synchronized
    fun start() {
        if (jobs.any { it.isActive }) return
        jobs = listOf(
            scope.launch { isolated("session") { session.connect() } },
            scope.launch { collectCommands() },
            scope.launch { collectDesired() },
            scope.launch { flushOnEveryConnection() },
            scope.launch { flushPeriodically() },
        )
    }

    /** Останавливает циклы и сессию (`offline`/`stop`, затем DISCONNECT). */
    suspend fun stop() {
        val running = synchronized(this) { jobs.also { jobs = emptyList() } }
        running.forEach { it.cancelAndJoin() }
        isolated("disconnect") { session.disconnect() }
    }

    private suspend fun collectCommands() {
        session.commands.collectResilient("commands") { cmd ->
            isolated("command") {
                handleCommand(cmd)
                flushIfPending()
            }
        }
    }

    private suspend fun collectDesired() {
        session.desired.collectResilient("desired") { shadow -> isolated("desired") { applyDesired(shadow) } }
    }

    private suspend fun flushOnEveryConnection() {
        connectedEpoch.collectResilient("connect-flush") { epoch ->
            if (epoch > 0) isolated("flush") { flush() }
        }
    }

    private suspend fun flushPeriodically() {
        while (currentCoroutineContext().isActive) {
            delay(flushIntervalMs)
            isolated("periodic-flush") { flushIfPending() }
        }
    }

    private suspend fun flushIfPending() {
        if (session.state.value == SessionState.CONNECTED && outbox.size() > 0) flush()
    }

    /** Сбой самого потока (не обработчика) не должен гасить цикл навсегда: повтор через секунду. */
    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collectResilient(name: String, block: suspend (T) -> Unit) {
        while (currentCoroutineContext().isActive) {
            isolated(name) { collect { block(it) } }
            delay(RESTART_DELAY_MS)
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // изоляция цикла (канон 78): в лог только класс
    private suspend fun isolated(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            log("$name: stray cancellation")
        } catch (e: Exception) {
            log("$name failed: ${e::class.java.simpleName}")
        }
    }

    companion object {
        const val DEFAULT_FLUSH_INTERVAL_MS = 60_000L
        private const val RESTART_DELAY_MS = 1_000L
    }
}
