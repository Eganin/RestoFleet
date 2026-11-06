package ru.restofleet.agent.data.lifecycle

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationManagerCompat

/** Реальные значения трёх флагов L4 из системных сервисов. */
class AndroidWizardPermissions(private val context: Context) : WizardPermissions {
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

    override fun ignoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
