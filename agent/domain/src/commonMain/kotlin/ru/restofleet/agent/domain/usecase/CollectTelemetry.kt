package ru.restofleet.agent.domain.usecase

import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.TelemetrySnapshot
import ru.restofleet.agent.domain.model.UnavailableReason
import ru.restofleet.agent.domain.port.SequenceSource
import ru.restofleet.agent.domain.port.TelemetrySource
import ru.restofleet.shared.kernel.Clock
import kotlin.coroutines.cancellation.CancellationException

/** Собирает снимок телеметрии; сбой одного считывания не влияет на остальные. */
class CollectTelemetry(
    private val source: TelemetrySource,
    private val clock: Clock,
    private val seq: SequenceSource,
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
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> isolated(block: suspend () -> Reading<T>): Reading<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Exception) {
            Reading.Unavailable(UnavailableReason.Failed(t.message ?: t::class.simpleName.orEmpty()))
        }
}
