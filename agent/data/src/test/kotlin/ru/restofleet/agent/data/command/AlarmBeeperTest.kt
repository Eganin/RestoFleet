package ru.restofleet.agent.data.command

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    private val sounds = mutableListOf<FakeSound>()
    private var startFailure: Throwable? = null

    private fun TestScope.beeper(): AlarmBeeper {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        return AlarmBeeper(context, scope) { attrs ->
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
        assertThat(result.value()).containsExactly("ringerMode", "vibrate", "usage", "alarm")
    }

    @Test
    fun ringerModesAreNamed() = runTest {
        val names = listOf(
            AudioManager.RINGER_MODE_NORMAL to "normal",
            AudioManager.RINGER_MODE_VIBRATE to "vibrate",
            AudioManager.RINGER_MODE_SILENT to "silent",
        ).map { (mode, _) ->
            audio.ringerMode = mode
            beeper().beep(1_000).value()["ringerMode"]
        }
        assertThat(names).containsExactly("normal", "vibrate", "silent").inOrder()
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
    fun realCancellationIsNotSwallowed() = runTest {
        val job = Job()
        val caller = CoroutineScope(StandardTestDispatcher(testScheduler) + job)
        // вызывающий отменён в тот момент, когда сигнал падает с CancellationException
        val cancelling = AlarmBeeper(context, CoroutineScope(StandardTestDispatcher(testScheduler))) {
            FakeSound(it) { job.cancel(); throw kotlinx.coroutines.CancellationException("cancelled") }
                .also { sound -> sounds += sound }
        }
        var thrown: Throwable? = null
        val run = caller.launch {
            try {
                cancelling.beep(1_000)
            } catch (e: kotlinx.coroutines.CancellationException) {
                thrown = e
                throw e
            }
        }
        run.join()

        assertThat(thrown).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        assertThat(sounds.single().released).isEqualTo(1)
    }

    @Test
    fun foreignCancellationExceptionBecomesFailure() = runTest {
        startFailure = kotlinx.coroutines.CancellationException("timeout of something else")

        val result = beeper().beep(1_000)

        assertThat(result.errorCode()).startsWith("beep_failed:")
        assertThat(sounds.single().released).isEqualTo(1)
    }

    @Test
    fun defaultSoundPlaysOnAlarmStreamAndReleases() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val b = AlarmBeeper(context, scope)
        val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
        org.robolectric.shadows.ShadowMediaPlayer.addMediaInfo(
            org.robolectric.shadows.util.DataSource.toDataSource(context, uri),
            org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(10_000, 0),
        )

        val result = b.beep(1_000)

        assertThat(result.value()["usage"]).isEqualTo("alarm")
        advanceTimeBy(1_100)
        scope.cancel()
    }
}
