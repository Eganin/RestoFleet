package ru.restofleet.server.api

import ru.restofleet.server.domain.command.Command
import ru.restofleet.server.domain.command.CommandStatus
import ru.restofleet.server.domain.device.Device
import ru.restofleet.server.domain.device.DeviceInfo
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.device.DeviceTransport
import ru.restofleet.server.domain.ingest.TelemetrySnapshot
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.server.domain.shadow.DefaultDesired
import ru.restofleet.server.domain.shadow.Shadow
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue

/** Доменные объекты для ответов фейковых use cases. */
object Samples {
    const val RESTAURANT_ID = "0b6f2a8e-1c2d-4e5f-8a9b-0c1d2e3f4a5b"
    val deviceId: DeviceId = DeviceId.parse("d-1")!!

    val restaurant = Restaurant(
        id = RestaurantId(RESTAURANT_ID),
        code = "MSK-01",
        name = "Кафе",
        city = "Москва",
        address = "ул. Тестовая, 1",
        timezone = "Europe/Moscow",
        createdAt = 1_000,
        updatedAt = 2_000,
    )

    val device = Device(
        id = deviceId,
        restaurantId = restaurant.id,
        role = DeviceRole.KITCHEN,
        name = "Кухня-1",
        status = DeviceStatus.ONLINE,
        transport = DeviceTransport.MQTT,
        lastSeenAt = 5_000,
        decommissionedAt = null,
        createdAt = 1_000,
        info = DeviceInfo("sdk_gphone64_arm64", "Google", "16", 36),
        agentVersion = "1.0.0",
        mode = "normal",
    )

    val telemetry = TelemetrySnapshot(
        deviceId = deviceId,
        ts = 1_793_865_630_000,
        seq = 42,
        batteryLevel = 71,
        charging = false,
        tempC = 33.5,
        thermal = "NONE",
        rssi = -58,
        freeMb = 2_048,
        availMb = 900,
        foregroundApp = "com.android.chrome",
        payload = "{}",
    )

    val shadow = Shadow(
        deviceId = deviceId,
        desired = DefaultDesired.document,
        desiredVersion = 2,
        desiredUpdatedAt = 3_000,
        reported = ShadowDocument(mapOf("telemetry" to ShadowValue.Obj(mapOf("intervalSec" to ShadowValue.Num(30))))),
        reportedVersion = 1,
        appliedAt = 2_500,
        reportedAt = 2_600,
        errors = emptyList(),
    )

    val command = Command(
        cmdId = "c-1",
        deviceId = deviceId,
        type = "ping",
        params = ShadowDocument.EMPTY,
        status = CommandStatus.QUEUED,
        createdAt = 10_000,
        expiresAt = 310_000,
        deliveredAt = null,
        finishedAt = null,
        result = null,
        reason = null,
    )
}
