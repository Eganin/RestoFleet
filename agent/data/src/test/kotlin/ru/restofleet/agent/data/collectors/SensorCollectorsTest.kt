package ru.restofleet.agent.data.collectors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager
import org.robolectric.util.ReflectionHelpers
import ru.restofleet.agent.data.platform.FreeFallDetector
import ru.restofleet.agent.domain.model.LightLevel
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SensorCollectorsTest {
    private val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val shadow: ShadowSensorManager = shadowOf(sm)

    private fun sensorEvent(size: Int): SensorEvent =
        ReflectionHelpers.callConstructor(
            SensorEvent::class.java,
            ReflectionHelpers.ClassParameter.from(Int::class.java, size),
        )

    private fun addSensor(type: Int): Sensor = ShadowSensor.newInstance(type).also { shadow.addSensor(it) }

    @Test
    fun noLightSensor() = runTest {
        // Robolectric по умолчанию без датчика освещённости
        assertThat(LightSensorCollector(appContext).read())
            .isEqualTo(Reading.Unavailable(UnavailableReason.NoSensor))
    }

    @Test
    fun lightSensorWithoutEventTimesOutWithoutThrowing() = runTest {
        addSensor(Sensor.TYPE_LIGHT)
        assertThat(LightSensorCollector(appContext, 3_000L).read())
            .isEqualTo(Reading.Unavailable(UnavailableReason.Failed("no reading in 3 s")))
    }

    @Test
    fun lightSensorReturnsFirstValue() = runTest {
        val sensor = addSensor(Sensor.TYPE_LIGHT)
        val result = async { LightSensorCollector(appContext).read() }
        runCurrent()
        val event = sensorEvent(1)
        event.sensor = sensor
        event.values[0] = 120f
        shadow.sendSensorEventToListeners(event)
        assertThat(result.await()).isEqualTo(Reading.Available(LightLevel(120f)))
    }

    @Test
    fun motionSupportReflectsSensors() {
        val collector = MotionCollector(appContext, FreeFallDetector())
        assertThat(collector.hasSignificantMotion()).isFalse()
        assertThat(collector.hasAccelerometer()).isFalse()
        addSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        addSensor(Sensor.TYPE_ACCELEROMETER)
        assertThat(collector.hasSignificantMotion()).isTrue()
        assertThat(collector.hasAccelerometer()).isTrue()
    }

    @Test
    fun freeFallIsEmittedOnceUntilRecovery() = runTest {
        val accel = addSensor(Sensor.TYPE_ACCELEROMETER)
        MotionCollector(appContext, FreeFallDetector(minDurationMs = 100)).motionEvents().test {
            fun sample(z: Float, tsMs: Long) {
                val e = sensorEvent(3)
                e.sensor = accel
                e.values[2] = z
                e.timestamp = tsMs * 1_000_000L
                shadow.sendSensorEventToListeners(e)
            }
            sample(0f, 0)
            sample(0f, 150)
            assertThat(awaitItem()).isEqualTo(MotionEvent.FREE_FALL)
            sample(0f, 300)
            sample(9.8f, 400)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun listenersAreRemovedAfterCancel() = runTest {
        addSensor(Sensor.TYPE_ACCELEROMETER)
        MotionCollector(appContext, FreeFallDetector()).motionEvents().test {
            assertThat(shadow.listeners).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(shadow.listeners).isEmpty()
    }

    @Test
    fun lightListenerIsRemovedAfterTimeout() = runTest {
        addSensor(Sensor.TYPE_LIGHT)
        LightSensorCollector(appContext, 3_000L).read()
        assertThat(shadow.listeners).isEmpty()
    }

    @Test
    fun lightCollectorDoesNotThrowOnPlatformFailure() = runTest {
        val broken = object : android.content.ContextWrapper(appContext) {
            override fun getSystemService(name: String): Any = throw IllegalStateException("sensor service down")
        }
        assertThat(LightSensorCollector(broken).read())
            .isEqualTo(Reading.Unavailable(UnavailableReason.Failed("sensor service down")))
    }
}
