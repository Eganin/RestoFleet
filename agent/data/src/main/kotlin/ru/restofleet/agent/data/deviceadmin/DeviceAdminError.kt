package ru.restofleet.agent.data.deviceadmin

import ru.restofleet.shared.kernel.DomainError

/** Ошибки адаптера Device Owner; [code] уходит в `reason` результата команды и не содержит секретов. */
sealed interface DeviceAdminError : DomainError {
    /** Приложение не Device Owner (или потеряло роль во время операции). */
    data object NotDeviceOwner : DeviceAdminError {
        override val code: String = "not_device_owner"
    }

    /** SHA-256 скачанного файла не совпал с ожидаемым: установка не начиналась. */
    data object HashMismatch : DeviceAdminError {
        override val code: String = "hash_mismatch"
    }

    data class DownloadFailed(val reason: String) : DeviceAdminError {
        override val code: String get() = "download_failed:$reason"
    }

    /** [status] — `STATUS_*` установщика или код из [InstallStatusMapper]; [message] — только для журнала. */
    data class InstallFailed(val status: Int, val message: String?) : DeviceAdminError {
        override val code: String get() = "install_failed:$status"
    }

    data class Unsupported(val reason: String) : DeviceAdminError {
        override val code: String get() = reason
    }
}
