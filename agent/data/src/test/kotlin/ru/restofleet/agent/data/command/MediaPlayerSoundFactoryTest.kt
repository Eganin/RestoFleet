package ru.restofleet.agent.data.command

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaPlayerSoundFactoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val uri = Uri.parse("content://settings/system/alarm_alert")
    private val alarm = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()

    private class Boom : Error("fatal")

    private fun factory(player: MediaPlayer, sound: Uri? = uri) =
        MediaPlayerSoundFactory(context, { player }, { sound })

    private fun registerMedia() =
        ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, uri), ShadowMediaPlayer.MediaInfo(10_000, 0))

    private fun TestScope.beeper(factory: AlarmSoundFactory): AlarmBeeper {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return AlarmBeeper(context, CoroutineScope(dispatcher), dispatcher, factory)
    }

    @Test
    fun playerIsPreparedWithAlarmAttributesLoopingAndPlaysUntilReleased() {
        registerMedia()
        val player = MediaPlayer()
        val shadow = shadowOf(player)

        val sound = factory(player).create(alarm)

        assertThat(shadow.audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_ALARM)
        assertThat(player.isLooping).isTrue()
        assertThat(shadow.sourceUri).isEqualTo(uri)
        assertThat(shadow.state).isEqualTo(ShadowMediaPlayer.State.PREPARED)
        sound.start()
        assertThat(shadow.isReallyPlaying).isTrue()
        sound.release()
        assertThat(shadow.state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    @Test
    fun playerStopsAfterDurationThroughTheBeeper() = runTest {
        registerMedia()
        val player = MediaPlayer()
        val shadow = shadowOf(player)

        val result = beeper(factory(player)).beep(5_000)

        assertThat(result.value()["usage"]).isEqualTo("alarm")
        assertThat(shadow.audioAttributes.usage).isEqualTo(AudioAttributes.USAGE_ALARM)
        assertThat(shadow.isReallyPlaying).isTrue()
        advanceTimeBy(4_999)
        assertThat(shadow.isReallyPlaying).isTrue()
        advanceTimeBy(2)
        assertThat(shadow.state).isEqualTo(ShadowMediaPlayer.State.END)
    }

    private fun failingPlayer(step: MediaPlayer.() -> Unit): MediaPlayer =
        mockk<MediaPlayer>(relaxed = true).also { it.step() }

    @Test
    fun playerIsReleasedWhenAnyPreparationStepFails() {
        val failures = listOf<Pair<String, MediaPlayer.() -> Unit>>(
            "setDataSource SecurityException" to {
                every { setDataSource(any<Context>(), any<Uri>()) } throws SecurityException("denied")
            },
            "setDataSource IllegalArgumentException" to {
                every { setDataSource(any<Context>(), any<Uri>()) } throws IllegalArgumentException("bad uri")
            },
            "prepare IllegalStateException" to { every { prepare() } throws IllegalStateException("state") },
            "prepare IOException" to { every { prepare() } throws IOException("io") },
        )
        for ((name, step) in failures) {
            val player = failingPlayer(step)

            assertThrows(name, Exception::class.java) { factory(player).create(alarm) }

            verify(exactly = 1) { player.release() }
        }
    }

    @Test
    fun playerIsReleasedOnErrorAndTheErrorIsNotSwallowed() {
        val player = failingPlayer { every { prepare() } throws Boom() }

        assertThrows(Boom::class.java) { factory(player).create(alarm) }

        verify(exactly = 1) { player.release() }
    }

    @Test
    fun missingDefaultSoundIsAnExpectedRefusalAndCreatesNoPlayer() {
        var created = 0
        val factory = MediaPlayerSoundFactory(context, { created++; MediaPlayer() }, { null })

        assertThrows(NoAlarmSoundException::class.java) { factory.create(alarm) }

        assertThat(created).isEqualTo(0)
    }

    @Test
    fun preparationFailureBecomesFailureAndLeaksNothingThroughTheBeeper() = runTest {
        val player = failingPlayer { every { setDataSource(any<Context>(), any<Uri>()) } throws SecurityException() }

        val result = beeper(factory(player)).beep(1_000)

        assertThat(result.errorCode()).isEqualTo("beep_failed:SecurityException")
        verify(exactly = 1) { player.release() }
    }

    @Test
    fun defaultFactoryUsesTheSystemDefaultSound() {
        val player = failingPlayer { }
        val sound = MediaPlayerSoundFactory(context, { player }).create(alarm)
        sound.release()

        verify { player.setAudioAttributes(alarm) }
        verify { player.release() }
    }
}
