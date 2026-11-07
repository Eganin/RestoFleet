package ru.restofleet.agent.data.deviceadmin

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.RequiresApi
import ru.restofleet.agent.data.launch.resolveLaunchTarget
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
        when (val resolved = resolveLaunchTarget(context.packageManager, pkg, url)) {
            is Outcome.Success -> resolved
            is Outcome.Failure -> Outcome.Failure(DeviceAdminError.Unsupported(resolved.error.code))
        }

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
