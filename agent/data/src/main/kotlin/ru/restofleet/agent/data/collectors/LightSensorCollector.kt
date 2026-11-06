package ru.restofleet.agent.data.collectors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ru.restofleet.agent.domain.model.LightLevel
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason
import kotlin.coroutines.resume

/** R9: одно значение датчика освещённости; нет события за [timeoutMs] — `Failed`, а не исключение таймаута. */
class LightSensorCollector(
    private val context: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    suspend fun read(): Reading<LightLevel> = guarded {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
            ?: return@guarded Reading.Unavailable(UnavailableReason.NoSensor)
        val outcome = withTimeoutOrNull(timeoutMs) { awaitLux(sm, sensor) }
        when (outcome) {
            null -> Reading.Unavailable(UnavailableReason.Failed("no reading in ${timeoutMs / MS_IN_SEC} s"))
            REGISTRATION_FAILED -> Reading.Unavailable(UnavailableReason.Failed("sensor listener registration failed"))
            else -> Reading.Available(LightLevel(outcome))
        }
    }

    /** [REGISTRATION_FAILED] — `registerListener` вернул false (отличается от таймаута, где результат null). */
    private suspend fun awaitLux(sm: SensorManager, sensor: Sensor): Float = suspendCancellableCoroutine { cont ->
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                sm.unregisterListener(this)
                if (cont.isActive) cont.resume(event.values[0])
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        cont.invokeOnCancellation { sm.unregisterListener(listener) }
        if (!sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
            cont.resume(REGISTRATION_FAILED)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
        const val MS_IN_SEC = 1_000L
        const val REGISTRATION_FAILED = Float.NEGATIVE_INFINITY
    }
}
