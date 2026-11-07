package ru.restofleet.agent.data.lifecycle

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Все опрашиваемые разрешения: три флага L4 плюс то, что нужно для `Capabilities` (команды C1–C4). */
interface DevicePermissions : WizardPermissions {
    fun overlayGranted(): Boolean
    fun fullScreenIntentAllowed(): Boolean
    fun fineLocationGranted(): Boolean
}

/**
 * Реальные значения разрешений из системных сервисов; единственное место опроса (L4 и `AndroidCapabilitiesSource`).
 */
class AndroidWizardPermissions(private val context: Context) : DevicePermissions {
    override fun notificationsEnabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun usageAccessGranted(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return when (mode(appOps)) {
            AppOpsManager.MODE_ALLOWED -> true
            // MODE_DEFAULT: решает manifest-разрешение (так в документации Android; важно при выдаче под Device Owner).
            AppOpsManager.MODE_DEFAULT ->
                context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                    PackageManager.PERMISSION_GRANTED
            else -> false
        }
    }

    // unsafeCheckOpNoThrow с API 29 — единственный вариант, не регистрирующий вызов как «использование»;
    // при compileSdk 37 помечен deprecated без замены с тем же смыслом, поэтому подавляем точечно.
    @Suppress("DEPRECATION")
    private fun mode(appOps: AppOpsManager): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }

    override fun overlayGranted(): Boolean = Settings.canDrawOverlays(context)

    // С API 34 право отзывается пользователем (`canUseFullScreenIntent`); раньше это обычное разрешение манифеста.
    override fun fullScreenIntentAllowed(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            NotificationManagerCompat.from(context).canUseFullScreenIntent()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.USE_FULL_SCREEN_INTENT) ==
                PackageManager.PERMISSION_GRANTED
        }

    override fun fineLocationGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun ignoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
