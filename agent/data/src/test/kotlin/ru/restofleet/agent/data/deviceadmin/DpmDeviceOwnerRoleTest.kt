package ru.restofleet.agent.data.deviceadmin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DpmDeviceOwnerRoleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(context.packageName, "ru.restofleet.agent.AdminReceiver")

    @Test
    fun roleFollowsThePolicyManagerOnEveryCall() {
        val role = DpmDeviceOwnerRole(dpm, context.packageName)
        assertThat(role.isDeviceOwner()).isFalse()

        shadowOf(dpm).setDeviceOwner(admin)

        assertThat(role.isDeviceOwner()).isTrue()
    }

    @Test
    fun onlyTheOwnPackageCounts() {
        shadowOf(dpm).setDeviceOwner(admin)

        assertThat(DpmDeviceOwnerRole(dpm, "some.other.pkg").isDeviceOwner()).isFalse()
    }
}
