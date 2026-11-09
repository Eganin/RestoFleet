package ru.restofleet.agent.data.deviceadmin

import ru.restofleet.agent.domain.model.AppVersion

/** Версия установленного пакета (`null` — пакета нет или он не виден приложению). */
fun interface InstalledVersions {
    fun version(pkg: String): AppVersion?
}
