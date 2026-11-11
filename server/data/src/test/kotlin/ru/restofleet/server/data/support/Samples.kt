package ru.restofleet.server.data.support

import ru.restofleet.server.domain.command.Command
import ru.restofleet.server.domain.command.CommandStatus
import ru.restofleet.server.domain.device.Device
import ru.restofleet.server.domain.device.DeviceInfo
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.ingest.TelemetrySnapshot
import ru.restofleet.server.domain.provisioning.ProvisioningToken
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue
import java.util.UUID

/** Образцы доменных объектов для интеграционных тестов (время — epoch ms около ноября 2026). */
object Samples {
    const val T0 = 1_793_865_600_000L

    fun uuid(): String = UUID.randomUUID().toString()

    fun deviceId(suffix: String = uuid()): DeviceId = DeviceId.parse("d-$suffix") ?: error("bad id")

    fun restaurant(code: String = "R-${uuid().take(8)}", at: Long = T0) = Restaurant(
        id = RestaurantId(uuid()),
        code = code,
        name = "Ресторан $code",
        city = "Казань",
        address = "ул. Баумана, 1",
        timezone = "Europe/Moscow",
        createdAt = at,
        updatedAt = at,
    )

    fun device(
        restaurant: Restaurant,
        id: DeviceId = deviceId(),
        role: DeviceRole = DeviceRole.KITCHEN,
        status: DeviceStatus = DeviceStatus.PROVISIONED,
        createdAt: Long = T0,
        info: DeviceInfo? = DeviceInfo("sdk_gphone64_arm64", "Google", "16", 36),
    ) = Device(
        id = id,
        restaurantId = restaurant.id,
        role = role,
        name = "Кухня-1",
        status = status,
        transport = null,
        lastSeenAt = null,
        decommissionedAt = null,
        createdAt = createdAt,
        info = info,
        agentVersion = "0.2.0",
        mode = null,
    )

    fun token(
        restaurant: Restaurant,
        hash: String,
        expiresAt: Long = T0 + 86_400_000,
        role: DeviceRole = DeviceRole.HALL,
    ) = ProvisioningToken(
        id = uuid(),
        tokenHash = hash,
        restaurantId = restaurant.id,
        role = role,
        name = "Зал-2",
        createdAt = T0,
        expiresAt = expiresAt,
        usedAt = null,
        deviceId = null,
    )

    fun telemetry(id: DeviceId, ts: Long, seq: Long = ts / 1_000, battery: Int? = 80) = TelemetrySnapshot(
        deviceId = id,
        ts = ts,
        seq = seq,
        batteryLevel = battery,
        charging = true,
        tempC = 31.5,
        thermal = "NONE",
        rssi = -60,
        freeMb = 1_024,
        availMb = 512,
        foregroundApp = "com.android.chrome",
        payload = """{"ts":$ts,"seq":$seq}""",
    )

    fun command(
        id: DeviceId,
        cmdId: String = "c-${uuid()}",
        status: CommandStatus = CommandStatus.QUEUED,
        createdAt: Long = T0,
        expiresAt: Long = T0 + 300_000,
    ) = Command(
        cmdId = cmdId,
        deviceId = id,
        type = "ping",
        params = ShadowDocument(mapOf("n" to ShadowValue.Num(1))),
        status = status,
        createdAt = createdAt,
        expiresAt = expiresAt,
        deliveredAt = null,
        finishedAt = null,
        result = null,
        reason = null,
    )
}
