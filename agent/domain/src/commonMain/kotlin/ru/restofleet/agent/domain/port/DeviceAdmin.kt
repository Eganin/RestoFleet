package ru.restofleet.agent.domain.port

import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Операции Device Owner. Вызываются только после проверки режима в `Capabilities.check`. */
interface DeviceAdmin {
    suspend fun isDeviceOwner(): Boolean
    suspend fun reboot(): Outcome<Unit, DomainError>
    suspend fun installApk(url: String, sha256: String): Outcome<Unit, DomainError>
    suspend fun startLockTask(pkg: String, url: String?): Outcome<Unit, DomainError>

    /** Самовыдача разрешений (DO); ключ — имя разрешения, значение — выдано ли. */
    suspend fun grantSelfPermissions(): Map<String, Boolean>
}
