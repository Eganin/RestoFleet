package ru.restofleet.agent

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat

/**
 * Шов над `startForeground`: система вправе отказать (запрет FGS из фона, отсутствующий тип и т.п.), и сервис
 * должен пережить отказ. В тестах заменяется стартером, который бросает.
 */
fun interface ForegroundStarter {
    fun start(service: Service, id: Int, notification: Notification)
}

/** Продакшн-стартер: `ServiceCompat.startForeground` с типом specialUse. */
class ServiceCompatForegroundStarter : ForegroundStarter {
    override fun start(service: Service, id: Int, notification: Notification) {
        ServiceCompat.startForeground(service, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }
}

/** Запрет старта FGS из фона (API 31+); отличает отказ системы от прочих сбоев запуска. */
internal fun isForegroundStartNotAllowed(e: RuntimeException): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException
