package ru.restofleet.agent.data.command

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import ru.restofleet.agent.data.deviceadmin.LockTaskState
import ru.restofleet.agent.data.lifecycle.AndroidWizardPermissions
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Permission

/** В Robolectric 4.17 `canUseFullScreenIntent` не эмулируется (всегда `false`): управляемая подмена для теста. */
@Implements(value = android.app.NotificationManager::class)
class FullScreenAwareNotificationManager : org.robolectric.shadows.ShadowNotificationManager() {
    @Implementation
    fun canUseFullScreenIntent(): Boolean = allowed

    companion object {
        var allowed = false
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidCapabilitiesSourceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = context as Application
    private var owner = false
    private var api = 34
    private var kioskActive = false
    private var kioskAllowed = emptySet<String>()
    private val lockTask = object : LockTaskState {
        override fun isActive() = kioskActive

        override fun allowedPackages() = kioskAllowed
    }

    private fun current() = runBlocking {
        AndroidCapabilitiesSource({ owner }, AndroidWizardPermissions(context), lockTask, { api }).current()
    }

    private fun usageAccess(mode: Int) = shadowOf(context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
        .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, mode)

    @Test
    fun modeAndApiComeFromRoleAndApiLevel() {
        assertThat(current().mode).isEqualTo(AgentMode.NORMAL)
        owner = true
        api = 33
        assertThat(current().mode).isEqualTo(AgentMode.DEVICE_OWNER)
        assertThat(current().apiLevel).isEqualTo(33)
    }

    @Test
    fun noKioskByDefault() {
        assertThat(current().kioskActive).isFalse()
        assertThat(current().kioskPackages).isEmpty()
    }

    @Test
    fun kioskStateAndAllowlistComeFromLockTaskState() {
        kioskActive = true
        kioskAllowed = setOf(context.packageName, "ru.restofleet.dummy.waiter")

        val caps = current()

        assertThat(caps.kioskActive).isTrue()
        assertThat(caps.kioskPackages).containsExactly(context.packageName, "ru.restofleet.dummy.waiter")
    }

    @Test
    fun allowlistIsNotReadWhenThereIsNoKiosk() {
        kioskActive = false
        kioskAllowed = setOf("a.b")

        assertThat(current().kioskPackages).isEmpty()
    }

    @Test
    fun nothingGrantedByDefaultExceptNotifications() {
        usageAccess(AppOpsManager.MODE_DEFAULT)
        assertThat(current().granted).containsExactly(Permission.POST_NOTIFICATIONS)
    }

    @Test
    fun notificationsAreReadFromNotificationManager() {
        shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .setNotificationsEnabled(false)
        assertThat(current().granted).doesNotContain(Permission.POST_NOTIFICATIONS)
    }

    @Test
    fun usageAccessFromAppOps() {
        usageAccess(AppOpsManager.MODE_ALLOWED)
        assertThat(current().granted).contains(Permission.USAGE_ACCESS)
        usageAccess(AppOpsManager.MODE_IGNORED)
        assertThat(current().granted).doesNotContain(Permission.USAGE_ACCESS)
    }

    @Test
    fun overlayFromSettings() {
        shadowOf(app).grantPermissions(Manifest.permission.SYSTEM_ALERT_WINDOW)
        shadowx(true)
        assertThat(current().granted).contains(Permission.SYSTEM_ALERT_WINDOW)
        shadowx(false)
        assertThat(current().granted).doesNotContain(Permission.SYSTEM_ALERT_WINDOW)
    }

    private fun shadowx(value: Boolean) = org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(value)

    @Test
    @Config(shadows = [FullScreenAwareNotificationManager::class])
    fun fullScreenIntentOnApi34UsesNotificationManager() {
        FullScreenAwareNotificationManager.allowed = true
        assertThat(current().granted).contains(Permission.FULL_SCREEN_INTENT)
        FullScreenAwareNotificationManager.allowed = false
        assertThat(current().granted).doesNotContain(Permission.FULL_SCREEN_INTENT)
    }

    @Test
    @Config(sdk = [33])
    fun fullScreenIntentBelowApi34FollowsManifestPermission() {
        assertThat(current().granted).doesNotContain(Permission.FULL_SCREEN_INTENT)
        shadowOf(app).grantPermissions(Manifest.permission.USE_FULL_SCREEN_INTENT)
        assertThat(current().granted).contains(Permission.FULL_SCREEN_INTENT)
    }

    @Test
    @Config(sdk = [33])
    fun notificationsOnApi33FollowTheRuntimePermissionThroughNotificationManager() {
        assertThat(current().granted).contains(Permission.POST_NOTIFICATIONS)
        shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .setNotificationsEnabled(false)
        assertThat(current().granted).doesNotContain(Permission.POST_NOTIFICATIONS)
    }

    @After
    fun resetShadowState() {
        FullScreenAwareNotificationManager.allowed = false
    }

    @Test
    fun fineLocationFromRuntimePermission() {
        assertThat(current().granted).doesNotContain(Permission.FINE_LOCATION)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertThat(current().granted).contains(Permission.FINE_LOCATION)
    }

    @Test
    fun batteryOptimizationExemption() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(pm).setIgnoringBatteryOptimizations(context.packageName, true)
        assertThat(current().granted).contains(Permission.IGNORE_BATTERY_OPT)
        shadowOf(pm).setIgnoringBatteryOptimizations(context.packageName, false)
        assertThat(current().granted).doesNotContain(Permission.IGNORE_BATTERY_OPT)
    }
}
