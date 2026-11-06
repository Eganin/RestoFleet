package ru.restofleet.agent.data.lifecycle

import android.app.AppOpsManager
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Process

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidWizardPermissionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val permissions = AndroidWizardPermissions(context)

    @Test
    fun usageAccessReflectsAppOpMode() {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED,
        )
        assertThat(permissions.usageAccessGranted()).isTrue()

        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_DEFAULT,
        )
        assertThat(permissions.usageAccessGranted()).isFalse()
    }

    @Test
    fun batteryOptimizationReflectsPowerManager() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(pm).setIgnoringBatteryOptimizations(context.packageName, true)
        assertThat(permissions.ignoringBatteryOptimizations()).isTrue()

        shadowOf(pm).setIgnoringBatteryOptimizations(context.packageName, false)
        assertThat(permissions.ignoringBatteryOptimizations()).isFalse()
    }

    @Test
    fun notificationsEnabledByDefaultOnRobolectric() {
        assertThat(permissions.notificationsEnabled()).isTrue()
    }
}
