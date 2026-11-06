package ru.restofleet.agent.data.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidCpuWakeLockTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lock = AndroidCpuWakeLock(context)

    @Test
    fun notHeldUntilRequested() {
        assertThat(lock.isHeld).isFalse()
    }

    @Test
    fun holdTakesNamedPartialWakeLock() {
        lock.hold(600_000)

        val latest = ShadowPowerManager.getLatestWakeLock()
        assertThat(latest.isHeld).isTrue()
        assertThat(lock.isHeld).isTrue()
        val shadow = shadowOf(latest)
        assertThat(shadow.tag).isEqualTo("RestoFleet:agent")
        assertThat(shadow.isReferenceCounted).isFalse()
    }

    @Test
    fun repeatedHoldIsNotReferenceCounted() {
        lock.hold(600_000)
        lock.hold(600_000)
        lock.hold(600_000)

        lock.release()

        assertThat(lock.isHeld).isFalse()
    }

    @Test
    fun releaseWithoutHoldIsHarmless() {
        lock.release()

        assertThat(lock.isHeld).isFalse()
    }
}
