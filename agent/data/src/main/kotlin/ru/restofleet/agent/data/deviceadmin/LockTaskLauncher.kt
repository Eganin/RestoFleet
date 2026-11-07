package ru.restofleet.agent.data.deviceadmin

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.annotation.RequiresApi
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.shared.kernel.Outcome

/**
 * Вход для API 26–27, где `ActivityOptions.setLockTaskEnabled` недоступен (появился в API 28): реализуется в
 * `agent:app` через `LockTaskActivity`, которая сама вызывает `startLockTask()` и запускает целевое приложение.
 */
fun interface LegacyLockTaskEntry {
    fun intent(pkg: String, url: String?): Intent
}

/** Определяет Intent целевого приложения для режима киоска (lock task) и запускает его. */
class LockTaskLauncher(
    private val context: Context,
    private val legacyEntry: LegacyLockTaskEntry,
    private val apiLevel: ApiLevel = ApiLevel.Device,
    private val starter: (Intent, Bundle?) -> Unit = { intent, options -> context.startActivity(intent, options) },
) {
    /** Intent целевого приложения; пакет должен быть установлен. */
    fun target(pkg: String, url: String?): Outcome<Intent, DeviceAdminError> =
        resolveLockTaskTarget(context.packageManager, pkg, url)

    /** Запускает [target] в lock task: на API 28+ через `ActivityOptions`, на 26–27 через вход приложения. */
    @Suppress("SwallowedException") // тип исключения и есть причина отказа
    @SuppressLint("NewApi") // API проверяется через apiLevel
    fun start(target: Intent, pkg: String, url: String?): Outcome<Unit, DeviceAdminError> =
        try {
            if (apiLevel.current() >= LOCK_TASK_OPTIONS_API) {
                starter(Intent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), lockTaskOptions())
            } else {
                starter(legacyEntry.intent(pkg, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null)
            }
            Outcome.Success(Unit)
        } catch (e: SecurityException) {
            // Пакет не в списке lock task (или роль DO потеряна)
            Outcome.Failure(DeviceAdminError.Unsupported("lock_task_not_permitted"))
        } catch (e: ActivityNotFoundException) {
            Outcome.Failure(DeviceAdminError.Unsupported("activity_not_found"))
        }

    @RequiresApi(LOCK_TASK_OPTIONS_API)
    private fun lockTaskOptions(): Bundle = ActivityOptions.makeBasic().setLockTaskEnabled(true).toBundle()

    private companion object {
        const val LOCK_TASK_OPTIONS_API = 28
    }
}

/**
 * Intent целевого приложения: `ACTION_VIEW` с адресом, ограниченный пакетом [pkg], либо его стандартный launch-intent.
 * Общая для [LockTaskLauncher] и `LockTaskActivity`, чтобы intent не передавался между ними вложенным extra.
 */
fun resolveLockTaskTarget(pm: PackageManager, pkg: String, url: String?): Outcome<Intent, DeviceAdminError> {
    if (!isInstalled(pm, pkg)) return Outcome.Failure(DeviceAdminError.Unsupported("package_not_installed"))
    val intent = if (url != null) {
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
    } else {
        pm.getLaunchIntentForPackage(pkg)
    }
    return intent?.let { Outcome.Success(it) } ?: Outcome.Failure(DeviceAdminError.Unsupported("no_launch_intent"))
}

// getPackageInfo(String, Int) работает на всех API; перегрузка с флагами появилась только в 33
@Suppress("DEPRECATION", "SwallowedException")
private fun isInstalled(pm: PackageManager, pkg: String): Boolean =
    try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
