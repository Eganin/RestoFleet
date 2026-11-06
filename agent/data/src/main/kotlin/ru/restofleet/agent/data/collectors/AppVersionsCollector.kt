package ru.restofleet.agent.data.collectors

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import ru.restofleet.agent.domain.model.AppVersion
import ru.restofleet.agent.domain.model.Reading

/** R7: версии отслеживаемых пакетов; отсутствующие пропускаются. Видимость пакетов — `<queries>` манифеста. */
class AppVersionsCollector(private val context: Context) {
    fun read(watched: List<String>): Reading<List<AppVersion>> = guarded {
        Reading.Available(watched.mapNotNull { version(it) })
    }

    fun version(pkg: String): AppVersion? = try {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        AppVersion(pkg, info.versionName, PackageInfoCompat.getLongVersionCode(info))
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
