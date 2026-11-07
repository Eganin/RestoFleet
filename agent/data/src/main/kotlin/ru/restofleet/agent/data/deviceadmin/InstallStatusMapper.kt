package ru.restofleet.agent.data.deviceadmin

import ru.restofleet.shared.kernel.Outcome

/**
 * Перевод `PackageInstaller.EXTRA_STATUS` в итог операции. `STATUS_SUCCESS` (0) — успех; `STATUS_PENDING_USER_ACTION`
 * (-1) для Device Owner не должен случаться, поэтому это сбой с пометкой `pending_user_action`; остальные коды
 * (1..7 по документации, а также неизвестные будущие) сохраняются как есть вместе с сообщением системы.
 */
object InstallStatusMapper {
    /** Собственные коды: не пересекаются с `PackageInstaller.STATUS_*` (-2..8 в API 36). */
    const val TIMEOUT = -100
    const val EXCEPTION = -101

    private const val STATUS_SUCCESS = 0
    private const val STATUS_PENDING_USER_ACTION = -1

    fun map(status: Int, message: String?): Outcome<Unit, DeviceAdminError> = when (status) {
        STATUS_SUCCESS -> Outcome.Success(Unit)
        STATUS_PENDING_USER_ACTION ->
            Outcome.Failure(DeviceAdminError.InstallFailed(status, "pending_user_action"))
        else -> Outcome.Failure(DeviceAdminError.InstallFailed(status, message))
    }
}
