package ru.restofleet.agent.domain.port

import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Операции Device Owner. Вызываются только после проверки режима в `Capabilities.check`. */
interface DeviceAdmin {
    suspend fun isDeviceOwner(): Boolean

    /**
     * Двухфазная перезагрузка (решение 142): метод **планирует** перезагрузку на несколько секунд вперёд и сразу
     * возвращает `Success` — устройство перезагрузится уже после возврата. `Failure` — планирование невозможно (нет
     * роли Device Owner). Результат команды нельзя отправить после перезагрузки, поэтому вызывающий обязан
     * сохранить итог персистентно (outbox) после этого вызова, а не публиковать его напрямую.
     */
    suspend fun reboot(): Outcome<Unit, DomainError>

    /**
     * Тихая установка APK пакета [pkg] с проверкой SHA-256. Ставится только отслеживаемый пакет; APK другого пакета
     * система отклоняет. `Success` несёт `package`, `versionCode`, `versionName` установленного приложения.
     */
    suspend fun installApk(pkg: String, url: String, sha256: String): Outcome<Map<String, String>, DomainError>
    suspend fun startLockTask(pkg: String, url: String?): Outcome<Unit, DomainError>

    /** Самовыдача разрешений (DO); ключ — имя разрешения, значение — выдано ли. */
    suspend fun grantSelfPermissions(): Map<String, Boolean>
}
