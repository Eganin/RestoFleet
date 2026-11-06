package ru.restofleet.agent.data.collectors

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import ru.restofleet.agent.data.platform.ResourceMapping
import ru.restofleet.agent.domain.model.DeviceResources
import ru.restofleet.agent.domain.model.Reading

/** R6: диск, ОЗУ, uptime, версия ОС, модель. */
class ResourceCollector(private val context: Context) {
    fun read(): Reading<DeviceResources> = guarded {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        Reading.Available(
            DeviceResources(
                freeStorageMb = ResourceMapping.bytesToMb(StatFs(Environment.getDataDirectory().path).availableBytes),
                availMemoryMb = ResourceMapping.bytesToMb(memory.availMem),
                uptimeSec = ResourceMapping.uptimeSec(SystemClock.elapsedRealtime()),
                osVersion = Build.VERSION.RELEASE,
                apiLevel = Build.VERSION.SDK_INT,
                model = Build.MODEL,
                manufacturer = Build.MANUFACTURER,
            ),
        )
    }
}
