package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.TelemetrySnapshot
import ru.restofleet.agent.domain.model.UnavailableReason
import ru.restofleet.agent.domain.port.SequenceSource
import ru.restofleet.agent.domain.port.TelemetrySource
import ru.restofleet.shared.kernel.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Собирает снимок телеметрии; сбой или зависание одного считывания не влияют на остальные: каждое считывание
 * ограничено [readingTimeoutMs] (по таймауту — `Failed("timeout N ms")`).
 */
class CollectTelemetry(
    private val source: TelemetrySource,
    private val clock: Clock,
    private val seq: SequenceSource,
    private val readingTimeoutMs: Long = DEFAULT_READING_TIMEOUT_MS,
) {
    suspend operator fun invoke(watchedApps: List<String>): TelemetrySnapshot {
        val unavailable = mutableMapOf<String, UnavailableReason>()

        suspend fun <T> read(key: String, block: suspend () -> Reading<T>): T? =
            when (val reading = isolated(block)) {
                is Reading.Available -> reading.value
                is Reading.Unavailable -> {
                    unavailable[key] = reading.reason
                    null
                }
            }

        return TelemetrySnapshot(
            ts = clock.nowMillis(),
            seq = seq.next(),
            battery = read("battery") { source.battery() },
            thermal = read("thermal") { source.thermal() },
            network = read("network") { source.network() },
            resources = read("resources") { source.resources() },
            foregroundApp = read("foregroundApp") { source.foregroundApp() },
            apps = read("apps") { source.apps(watchedApps) }.orEmpty(),
            unavailable = unavailable,
        )
    }

    // Ловим именно Exception: Error (OOM и т.п.) — состояние процесса, не сбой считывания, и пробрасывается.
    // CancellationException при активном вызывающем (чужой TimeoutCancellationException, отменённый future) —
    // сбой считывания; настоящая отмена вызывающего пробрасывается через ensureActive().
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> isolated(block: suspend () -> Reading<T>): Reading<T> =
        try {
            withTimeoutOrNull(readingTimeoutMs) { block() }
                ?: Reading.Unavailable(UnavailableReason.Failed("timeout $readingTimeoutMs ms"))
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            failed(e)
        } catch (t: Exception) {
            failed(t)
        }

    private fun failed(t: Exception): Reading.Unavailable =
        Reading.Unavailable(UnavailableReason.Failed(t.message ?: t::class.simpleName.orEmpty()))

    private companion object {
        const val DEFAULT_READING_TIMEOUT_MS = 5_000L
    }
}
