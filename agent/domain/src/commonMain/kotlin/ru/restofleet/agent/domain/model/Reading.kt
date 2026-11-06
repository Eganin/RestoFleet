package ru.restofleet.agent.domain.model

/** Результат считывания показателя: значение либо причина, по которой его получить нельзя. */
sealed interface Reading<out T> {
    data class Available<T>(val value: T) : Reading<T>
    data class Unavailable(val reason: UnavailableReason) : Reading<Nothing>
}

sealed interface UnavailableReason {
    data class ApiLevelTooLow(val required: Int, val actual: Int) : UnavailableReason
    data class PermissionMissing(val permission: String) : UnavailableReason
    data object NoSensor : UnavailableReason
    data object RequiresDeviceOwner : UnavailableReason
    data object EmulatorStub : UnavailableReason
    data class Failed(val message: String) : UnavailableReason
}
