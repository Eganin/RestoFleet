package ru.restofleet.agent.data.deviceadmin

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.shared.kernel.Outcome

/** Проверка на реальном `DevicePolicyManager` под Robolectric (`ShadowDevicePolicyManager`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DpmDeviceAdminRobolectricTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(context.packageName, "ru.restofleet.agent.AdminReceiver")

    private fun sut() = DpmDeviceAdmin(
        context, dpm, admin, mockk(), mockk(), LockTaskLauncher(context, { _, _ -> error("unused") }),
        ApiLevel { 34 },
    )

    @Test
    fun roleFollowsTheRealPolicyManager() = blocking {
        assertThat(sut().isDeviceOwner()).isFalse()
        assertThat(sut().reboot()).isEqualTo(Outcome.Failure(DeviceAdminError.NotDeviceOwner))
        assertThat(shadowOf(dpm).setDeviceOwner(admin)).isTrue()
        assertThat(sut().isDeviceOwner()).isTrue()
    }

    @Test
    fun grantReportsPolicyManagerAnswersForDeviceOwner() = blocking {
        shadowOf(dpm).setDeviceOwner(admin)
        val result = sut().grantSelfPermissions()
        assertThat(result[Manifest.permission.ACCESS_FINE_LOCATION]).isTrue()
        assertThat(result[Manifest.permission.READ_PHONE_STATE]).isTrue()
        assertThat(result[Manifest.permission.PACKAGE_USAGE_STATS]).isFalse()
    }

    private fun blocking(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { block() }
    }
}
