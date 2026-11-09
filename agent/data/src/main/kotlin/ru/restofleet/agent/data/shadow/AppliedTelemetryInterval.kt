package ru.restofleet.agent.data.shadow

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.restofleet.agent.domain.port.ShadowStore
import kotlin.coroutines.cancellation.CancellationException

/** Интервал цикла телеметрии: `telemetryIntervalSec` из сохранённой тени, иначе [defaultMs]. Не бросает. */
class AppliedTelemetryInterval(
    private val store: ShadowStore,
    private val defaultMs: Long,
) {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // сбой хранилища — значение по умолчанию
    suspend fun millis(): Long {
        val seconds = try {
            store.load()?.telemetryIntervalSec
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (e: Exception) {
            null
        }
        return if (seconds != null && seconds in RANGE_SEC) seconds * MS_IN_SECOND else defaultMs
    }

    private companion object {
        /** Диапазон, который пропускает `ApplyDesiredShadow`; всё вне него в файле — порча. */
        val RANGE_SEC = 10..3_600
        const val MS_IN_SECOND = 1_000L
    }
}
