package ru.restofleet.server.api

import ru.restofleet.server.domain.auth.usecase.Login
import ru.restofleet.server.domain.auth.usecase.RefreshSession
import ru.restofleet.server.domain.command.CommandReport
import ru.restofleet.server.domain.command.usecase.GetCommand
import ru.restofleet.server.domain.command.usecase.HandleCommandResult
import ru.restofleet.server.domain.command.usecase.SendCommand
import ru.restofleet.server.domain.device.usecase.DecommissionDevice
import ru.restofleet.server.domain.device.usecase.GetDevice
import ru.restofleet.server.domain.device.usecase.ListDevices
import ru.restofleet.server.domain.health.usecase.CheckHealth
import ru.restofleet.server.domain.ingest.DeviceDataBatch
import ru.restofleet.server.domain.ingest.usecase.GetEventHistory
import ru.restofleet.server.domain.ingest.usecase.GetProbeHistory
import ru.restofleet.server.domain.ingest.usecase.GetTelemetryHistory
import ru.restofleet.server.domain.polling.usecase.AuthenticateDevice
import ru.restofleet.server.domain.polling.usecase.PollCommands
import ru.restofleet.server.domain.polling.usecase.PollTelemetry
import ru.restofleet.server.domain.provisioning.usecase.IssueProvisioningTokens
import ru.restofleet.server.domain.provisioning.usecase.ProvisionDevice
import ru.restofleet.server.domain.restaurant.usecase.CreateRestaurant
import ru.restofleet.server.domain.restaurant.usecase.GetRestaurant
import ru.restofleet.server.domain.restaurant.usecase.ListRestaurants
import ru.restofleet.server.domain.restaurant.usecase.UpdateRestaurant
import ru.restofleet.server.domain.shadow.usecase.GetShadow
import ru.restofleet.server.domain.shadow.usecase.UpdateDesired
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome

/**
 * Всё, что нужно маршрутам: use cases приходят параметрами (арх. разд. 8), собирает их composition root
 * (`server:app`, Koin). Группы — по фичам, чтобы у функции маршрутов было немного параметров.
 */
class ApiUseCases(
    val auth: AuthUseCases,
    val restaurants: RestaurantUseCases,
    val devices: DeviceUseCases,
    val shadows: ShadowUseCases,
    val commands: CommandUseCases,
    val provisioning: ProvisioningUseCases,
    val poll: PollUseCases,
    val health: CheckHealth,
)

class AuthUseCases(val login: Login, val refresh: RefreshSession)

class RestaurantUseCases(
    val create: CreateRestaurant,
    val update: UpdateRestaurant,
    val get: GetRestaurant,
    val list: ListRestaurants,
)

class DeviceUseCases(
    val list: ListDevices,
    val get: GetDevice,
    val decommission: DecommissionDevice,
    val telemetry: GetTelemetryHistory,
    val probes: GetProbeHistory,
    val events: GetEventHistory,
)

class ShadowUseCases(val get: GetShadow, val update: UpdateDesired)

class CommandUseCases(val send: SendCommand, val get: GetCommand)

class ProvisioningUseCases(val issue: IssueProvisioningTokens, val provision: ProvisionDevice)

class PollUseCases(
    val authenticate: AuthenticateDevice,
    val telemetry: PollTelemetry,
    val commands: PollCommands,
    val result: HandleCommandResult,
    val decoder: DevicePayloadDecoder,
)

/**
 * Разбор тела REST-опроса устройства (FR-BE-54) — **теми же правилами**, что MQTT-ingest (реш. 241, 246). Порт
 * presentation-слоя: реализация — декодер `server:data`, подключает `server:app`. Отказ — машиночитаемая причина без
 * payload (`bad_json:…`, `bad_ts:…`, `nul_char`).
 */
interface DevicePayloadDecoder {
    fun telemetryBatch(deviceId: DeviceId, body: String): Outcome<DeviceDataBatch, String>

    fun commandReport(deviceId: DeviceId, body: String): Outcome<CommandReport, String>
}
