package ru.restofleet.server.domain.device

import ru.restofleet.server.domain.command.Command
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.shadow.DesiredState
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome

/**
 * Учётки устройств в брокере (Mosquitto Dynamic Security, FR-BR-04): клиент с `clientid = deviceId` и ролью
 * `device` (P5, P6). Все операции идемпотентны там, где это возможно: удаление и отключение отсутствующего клиента —
 * успех.
 */
interface BrokerAccounts {
    suspend fun createDevice(id: DeviceId, password: String): Outcome<Unit, ServerError.BrokerUnavailable>

    /** Отключает учётку и разрывает текущую сессию (FR-BE-04). */
    suspend fun disableDevice(id: DeviceId): Outcome<Unit, ServerError.BrokerUnavailable>

    /** Компенсация неудачного провижининга. */
    suspend fun deleteDevice(id: DeviceId): Outcome<Unit, ServerError.BrokerUnavailable>
}

/** Публикация в брокер от имени бэкенда (QoS 1). Вызывается только после commit транзакции (P10). */
interface MessagePublisher {
    /** `rf/v1/dev/{id}/shadow/desired`, retained (FR-BE-21). */
    suspend fun publishDesired(id: DeviceId, desired: DesiredState): Outcome<Unit, ServerError.BrokerUnavailable>

    /** Очистка retained `desired` и `status` пустым payload (P8, вывод из эксплуатации). */
    suspend fun clearRetained(id: DeviceId): Outcome<Unit, ServerError.BrokerUnavailable>

    /**
     * `rf/v1/dev/{id}/cmd/req` с `Response Topic` = свой `cmd/res`, `Correlation Data` = `cmdId` и
     * `Message Expiry Interval` до [Command.expiresAt] (FR-BE-40).
     */
    suspend fun publishCommand(command: Command): Outcome<Unit, ServerError.BrokerUnavailable>
}
