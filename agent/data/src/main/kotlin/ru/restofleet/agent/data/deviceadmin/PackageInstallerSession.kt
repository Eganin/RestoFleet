package ru.restofleet.agent.data.deviceadmin

import ru.restofleet.shared.kernel.Outcome
import java.io.File

/** Шов над `PackageInstaller`: записывает [apk] в сессию, коммитит и ждёт итог. Файл не удаляет. */
fun interface PackageInstallerSession {
    suspend fun install(apk: File): Outcome<Unit, DeviceAdminError>
}
