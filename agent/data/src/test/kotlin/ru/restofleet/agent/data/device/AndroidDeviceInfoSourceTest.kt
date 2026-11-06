package ru.restofleet.agent.data.device

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.restofleet.agent.domain.capability.AgentMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDeviceInfoSourceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun reportsPlatformValuesAndNormalModeByDefault() = runTest {
        val info = AndroidDeviceInfoSource(context, "0.1.0-mvp").deviceInfo()

        assertThat(info.apiLevel).isEqualTo(34)
        assertThat(info.model).isEqualTo(android.os.Build.MODEL)
        assertThat(info.isEmulator).isFalse()
        assertThat(info.agentVersion).isEqualTo("0.1.0-mvp")
        assertThat(info.mode).isEqualTo(AgentMode.NORMAL)
    }

    @Test
    fun deviceOwnerAppSwitchesModeToDeviceOwner() = runTest {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        shadowOf(dpm).setDeviceOwner(ComponentName(context.packageName, "ru.restofleet.agent.AdminReceiver"))

        val info = AndroidDeviceInfoSource(context, "0.1.0-mvp").deviceInfo()

        assertThat(info.mode).isEqualTo(AgentMode.DEVICE_OWNER)
    }
}
