package ru.restofleet.agent.data.deviceadmin

import ru.restofleet.shared.kernel.Outcome
import java.io.File

/**
 * Шов над `PackageInstaller`: записывает [apk] в сессию пакета [pkg], коммитит и ждёт итог. Файл не удаляет. Система
 * отклоняет APK другого пакета (`SessionParams.setAppPackageName`).
 */
fun interface PackageInstallerSession {
    suspend fun install(apk: File, pkg: String): Outcome<Unit, DeviceAdminError>
}
