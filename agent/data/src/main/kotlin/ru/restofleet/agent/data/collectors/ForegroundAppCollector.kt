package ru.restofleet.agent.data.collectors

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.agent.data.platform.ForegroundAppResolver
import ru.restofleet.agent.data.platform.UsageEventRaw
import ru.restofleet.agent.data.platform.UsageWindow
import ru.restofleet.agent.domain.model.ForegroundApp
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason
import ru.restofleet.shared.kernel.Clock

/**
 * R8: приложение на переднем плане по UsageEvents; нужен «Доступ к данным об использовании».
 *
 * Stateful: `UsageEvents` — только переходы, поэтому приложение, не уходившее с экрана дольше любого короткого окна,
 * событий не оставляет. Первый запрос идёт с момента загрузки устройства, следующие — с прошлого запроса с нахлёстом
 * ([UsageWindow]); последнее определённое приложение хранится здесь и остаётся ответом, пока нет новых событий.
 * Нужен один экземпляр на процесс (в Koin — `single`); вызовы цикла телеметрии и Lab сериализует `synchronized`.
 */
class ForegroundAppCollector(
    private val context: Context,
    private val clock: Clock,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    private val apiLevel: ApiLevel = ApiLevel.Device,
) {
    private var lastQueryAt: Long? = null
    private var lastApp: ForegroundApp? = null

    @Synchronized
    fun read(): Reading<ForegroundApp> = guarded {
        if (!hasUsageAccess()) {
            return@guarded Reading.Unavailable(UnavailableReason.PermissionMissing(PERMISSION))
        }
        val now = clock.nowMillis()
        val start = UsageWindow.startMillis(now, elapsedRealtime(), lastQueryAt)
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(start, now)
        val raw = mutableListOf<UsageEventRaw>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            raw += UsageEventRaw(event.packageName, event.eventType, event.timeStamp)
        }
        val app = ForegroundAppResolver.resolve(raw, lastApp)
        // Состояние обновляется только после успешного запроса: сбой binder не сдвигает окно и не теряет события.
        lastQueryAt = now
        lastApp = app
        app?.let { Reading.Available(it) }
            ?: Reading.Unavailable(UnavailableReason.Failed("no foreground activity since boot"))
    }

    @SuppressLint("NewApi") // unsafeCheckOpNoThrow только при apiLevel >= 29
    @Suppress("DEPRECATION") // checkOpNoThrow заменён unsafeCheckOpNoThrow только с API 29
    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val uid = Process.myUid()
        val mode = if (apiLevel.current() >= UNSAFE_CHECK_API) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, context.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, context.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkCallingOrSelfPermission(PERMISSION_FULL) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    private companion object {
        const val PERMISSION = "PACKAGE_USAGE_STATS"
        const val PERMISSION_FULL = "android.permission.PACKAGE_USAGE_STATS"
        const val UNSAFE_CHECK_API = 29
    }
}
