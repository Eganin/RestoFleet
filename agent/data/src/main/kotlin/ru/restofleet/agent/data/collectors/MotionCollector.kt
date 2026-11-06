package ru.restofleet.agent.data.collectors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import ru.restofleet.agent.data.platform.FreeFallDetector

/** Событие движения устройства. */
enum class MotionEvent { SIGNIFICANT_MOTION, FREE_FALL }

/** R11: значимое перемещение (SIGNIFICANT_MOTION) и свободное падение по акселерометру. */
class MotionCollector(
    private val context: Context,
    private val detector: FreeFallDetector,
) {
    private val sensorManager get() = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    fun hasSignificantMotion(): Boolean = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) != null

    fun hasAccelerometer(): Boolean = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null

    fun motionEvents(): Flow<MotionEvent> = callbackFlow {
        val sm = sensorManager
        val significant = sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        val accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val trigger = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent) {
                trySend(MotionEvent.SIGNIFICANT_MOTION)
                // Датчик одноразовый: перевзводим
                significant?.let { sm.requestTriggerSensor(this, it) }
            }
        }
        var falling = false
        val accel = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                val fall = detector.onSample(x, y, z, event.timestamp / NANOS_IN_MS)
                if (fall && !falling) trySend(MotionEvent.FREE_FALL)
                falling = fall
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        significant?.let { sm.requestTriggerSensor(trigger, it) }
        accelerometer?.let { sm.registerListener(accel, it, SensorManager.SENSOR_DELAY_GAME) }
        awaitClose {
            significant?.let { sm.cancelTriggerSensor(trigger, it) }
            sm.unregisterListener(accel)
        }
    }

    private companion object {
        const val NANOS_IN_MS = 1_000_000L
    }
}
