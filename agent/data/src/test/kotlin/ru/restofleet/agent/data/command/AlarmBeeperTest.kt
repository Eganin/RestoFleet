package ru.restofleet.agent.data.command

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmBeeperTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private class FakeSound(val attributes: AudioAttributes, val onStart: (() -> Unit)? = null) : AlarmSound {
        var started = false
        var released = 0
        override fun start() {
            started = true
            onStart?.invoke()
        }
        override fun release() { released++ }
    }

    /** Диспетчер «ввода-вывода»: на виртуальном времени, но помечает код, исполняемый через него. */
    private class MarkingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) =
            delegate.dispatch(context) {
                inside.set(true)
                try { block.run() } finally { inside.set(false) }
            }

        companion object {
            val inside: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        }
    }

    private val sounds = mutableListOf<FakeSound>()
    private var startFailure: Throwable? = null
    private var factoryFailure: Throwable? = null
    private val createdInsideIo = mutableListOf<Boolean>()

    private fun TestScope.beeper(): AlarmBeeper {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val io = MarkingDispatcher(StandardTestDispatcher(testScheduler))
        return AlarmBeeper(context, scope, io) { attrs ->
            createdInsideIo += MarkingDispatcher.inside.get()
            factoryFailure?.let { throw it }
            FakeSound(attrs) { startFailure?.let { e -> throw e } }.also { sounds += it }
        }
    }

    @Test
    fun usesAlarmUsageAndReportsRingerModeWithoutWaitingForTheEnd() = runTest {
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE

        val result = beeper().beep(5_000)

        val sound = sounds.single()
        assertThat(sound.attributes.usage).isEqualTo(AudioAttributes.USAGE_ALARM)
        assertThat(sound.started).isTrue()
        assertThat(sound.released).isEqualTo(0)
        assertThat(result.value()["ringerMode"]).isEqualTo("vibrate")
        assertThat(result.value()["usage"]).isEqualTo("alarm")
    }

    @Test
    fun resultCarriesAlarmStreamVolumeBecauseThatIsWhatMakesTheSignalSilent() = runTest {
        shadowOf(audio).setStreamMaxVolume(7)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)

        val silent = beeper().beep(1_000).value()
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0)
        val loud = beeper().beep(1_000).value()

        assertThat(silent["alarmVolume"]).isEqualTo("0")
        assertThat(loud["alarmVolume"]).isEqualTo("5")
        assertThat(loud["alarmVolumeMax"]).isEqualTo("7")
    }

    @Test
    fun ringerModesAreNamed() = runTest {
        val names = listOf(
            AudioManager.RINGER_MODE_NORMAL,
            AudioManager.RINGER_MODE_VIBRATE,
            AudioManager.RINGER_MODE_SILENT,
        ).map { mode ->
            audio.ringerMode = mode
            beeper().beep(1_000).value()["ringerMode"]
        }
        assertThat(names).containsExactly("normal", "vibrate", "silent").inOrder()
    }

    @Test
    fun blockingPreparationRunsOnTheInjectedIoDispatcherNotOnTheCaller() = runTest {
        beeper().beep(1_000)

        assertThat(createdInsideIo).containsExactly(true)
    }

    @Test
    fun soundIsReleasedAfterDuration() = runTest {
        val b = beeper()
        b.beep(5_000)

        advanceTimeBy(4_999)
        assertThat(sounds.single().released).isEqualTo(0)
        advanceTimeBy(2)
        assertThat(sounds.single().released).isEqualTo(1)
    }

    @Test
    fun repeatedBeepRestartsInsteadOfOverlapping() = runTest {
        val b = beeper()
        b.beep(5_000)
        advanceTimeBy(3_000)
        b.beep(5_000)

        assertThat(sounds).hasSize(2)
        assertThat(sounds[0].released).isEqualTo(1)
        advanceTimeBy(4_000) // старый таймер не должен остановить новый звук
        assertThat(sounds[1].released).isEqualTo(0)
        advanceTimeBy(1_100)
        assertThat(sounds[1].released).isEqualTo(1)
        assertThat(sounds[0].released).isEqualTo(1)
    }

    @Test
    fun startFailureReleasesTheSoundAndReturnsFailure() = runTest {
        startFailure = IllegalStateException("no audio")

        val result = beeper().beep(1_000)

        assertThat(result.errorCode()).isEqualTo("beep_failed:IllegalStateException")
        assertThat(sounds.single().released).isEqualTo(1)
    }

    @Test
    fun errorDuringStartReleasesTheSoundAndIsNotSwallowed() = runTest {
        startFailure = AssertionError("fatal")
        val b = beeper()

        val thrown = runCatching { b.beep(1_000) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(AssertionError::class.java)
        assertThat(sounds.single().released).isEqualTo(1)
    }

    @Test
    fun factoryRefusalsBecomeFailuresWithTheirOwnCodes() = runTest {
        factoryFailure = SecurityException("denied")
        assertThat(beeper().beep(1_000).errorCode()).isEqualTo("beep_failed:SecurityException")

        factoryFailure = NoAlarmSoundException()
        assertThat(beeper().beep(1_000).errorCode()).isEqualTo("no_alarm_sound")
    }

    @Test
    fun realCancellationIsNotSwallowed() = runTest {
        val job = Job()
        val caller = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
        // вызывающий отменён в тот момент, когда сигнал падает с CancellationException
        val cancelling = AlarmBeeper(
            context,
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            StandardTestDispatcher(testScheduler),
        ) {
            FakeSound(it) { job.cancel(); throw CancellationException("cancelled") }.also { sound -> sounds += sound }
        }
        var thrown: Throwable? = null
        val run = caller.launch {
            try {
                cancelling.beep(1_000)
            } catch (e: CancellationException) {
                thrown = e
                throw e
            }
        }
        run.join()

        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(sounds.single().released).isEqualTo(1)
    }

    @Test
    fun foreignCancellationExceptionBecomesFailure() = runTest {
        startFailure = CancellationException("timeout of something else")

        val result = beeper().beep(1_000)

        assertThat(result.errorCode()).startsWith("beep_failed:")
        assertThat(sounds.single().released).isEqualTo(1)
    }
}
