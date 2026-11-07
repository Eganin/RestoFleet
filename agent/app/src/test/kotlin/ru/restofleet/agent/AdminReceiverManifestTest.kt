package ru.restofleet.agent

import android.app.admin.DeviceAdminInfo
import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Проверка манифеста Device Owner: компонент, разрешение, разметка политик и debug-only `testOnly`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AdminReceiverManifestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun adminInfo(): android.content.pm.ResolveInfo =
        context.packageManager.queryBroadcastReceivers(
            Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED).setPackage(context.packageName),
            PackageManager.GET_META_DATA,
        ).single()

    @Test
    fun adminReceiverIsDeclaredWithBindDeviceAdminPermission() {
        val info = adminInfo().activityInfo
        assertThat(info.name).isEqualTo("ru.restofleet.agent.AdminReceiver")
        assertThat(info.permission).isEqualTo("android.permission.BIND_DEVICE_ADMIN")
        assertThat(info.exported).isTrue()
    }

    @Test
    fun deviceAdminXmlParsesAndRequestsNoLegacyPolicies() {
        val parsed = DeviceAdminInfo(context, adminInfo())
        assertThat(parsed.component).isEqualTo(ComponentName(context, AdminReceiver::class.java))
        for (policy in listOf(
            DeviceAdminInfo.USES_POLICY_LIMIT_PASSWORD,
            DeviceAdminInfo.USES_POLICY_WATCH_LOGIN,
            DeviceAdminInfo.USES_POLICY_RESET_PASSWORD,
            DeviceAdminInfo.USES_POLICY_FORCE_LOCK,
            DeviceAdminInfo.USES_POLICY_WIPE_DATA,
        )) {
            assertThat(parsed.usesPolicy(policy)).isFalse()
        }
    }

    @Test
    fun lockTaskActivityIsPrivateSingleTask() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, LockTaskActivity::class.java), 0)
        assertThat(info.exported).isFalse()
        assertThat(info.launchMode).isEqualTo(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK)
    }

    @Test
    fun debugBuildIsTestOnlySoDeviceOwnerCanBeRemoved() {
        assertThat(context.applicationInfo.flags and ApplicationInfo.FLAG_TEST_ONLY).isNotEqualTo(0)
    }

    @Test
    fun readPhoneStateIsDeclaredForSerialAndSelfGrant() {
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()
        assertThat(requested).contains("android.permission.READ_PHONE_STATE")
    }
}
