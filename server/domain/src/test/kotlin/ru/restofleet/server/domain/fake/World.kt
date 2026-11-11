package ru.restofleet.server.domain.fake

import ru.restofleet.server.domain.auth.AuthSettings
import ru.restofleet.server.domain.auth.SessionIssuer
import ru.restofleet.server.domain.command.Command
import ru.restofleet.server.domain.command.CommandStatus
import ru.restofleet.server.domain.device.Device
import ru.restofleet.server.domain.device.DeviceInfo
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.device.DeviceTransport
import ru.restofleet.server.domain.polling.PollingSettings
import ru.restofleet.server.domain.provisioning.BrokerEndpoint
import ru.restofleet.server.domain.provisioning.ProvisioningSettings
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.server.domain.shadow.DefaultDesired
import ru.restofleet.server.domain.shadow.Shadow
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.shadow.ShadowDocument

/** Все фейки одной «вселенной» теста и заготовки данных. */
class World {
    val db = InMemoryDb()
    val tx = FakeTransactionRunner(db)
    val clock = FakeClock()
    val ids = SequentialIds()
    val restaurants = FakeRestaurantRepository(db)
    val devices = FakeDeviceRepository(db)
    val credentials = FakeCredentialRepository(db)
    val tokens = FakeTokenRepository(db)
    val sessions = FakeSessionRepository(db)
    val shadows = FakeShadowRepository(db)
    val telemetry = FakeTelemetryRepository(db)
    val commands = FakeCommandRepository(db)
    val users = FakeUserRepository(db)
    val refreshTokens = FakeRefreshTokenRepository(db)
    val broker = FakeBrokerAccounts()
    val publisher = FakePublisher(db)
    val hasher = FakePasswordHasher()
    val secrets = FakeSecrets()
    val digest = fakeDigest
    val cache = FakeCredentialCache()
    val accessTokens = FakeAccessTokens()
    val provisioningSettings = ProvisioningSettings(BrokerEndpoint("10.0.2.2", 8883))
    val pollingSettings = PollingSettings()
    val authSettings = AuthSettings()
    val sessionIssuer = SessionIssuer(refreshTokens, accessTokens, secrets, digest, authSettings, ids)

    fun seed(change: (DbState) -> DbState) {
        db.state = change(db.state)
    }

    fun restaurant(id: String = "r-1", code: String = "MSK-001"): Restaurant {
        val r = Restaurant(RestaurantId(id), code, "Ресторан $code", "Москва", "ул. Тестовая, 1", "Europe/Moscow", 1, 1)
        seed { it.copy(restaurants = it.restaurants + (r.id to r)) }
        return r
    }

    fun device(
        id: String = "d-1",
        restaurantId: String = "r-1",
        status: DeviceStatus = DeviceStatus.PROVISIONED,
        transport: DeviceTransport? = null,
        lastSeenAt: Long? = null,
        createdAt: Long = 10,
    ): Device {
        val d = Device(
            id = deviceId(id),
            restaurantId = RestaurantId(restaurantId),
            role = DeviceRole.KITCHEN,
            name = null,
            status = status,
            transport = transport,
            lastSeenAt = lastSeenAt,
            decommissionedAt = if (status == DeviceStatus.DECOMMISSIONED) 5 else null,
            createdAt = createdAt,
            info = INFO,
            agentVersion = "1.0.0",
            mode = null,
        )
        seed { it.copy(devices = it.devices + (d.id to d)) }
        return d
    }

    fun credential(id: String, password: String, revokedAt: Long? = null) = seed {
        it.copy(credentials = it.credentials + (deviceId(id) to StoredCredential("bcrypt($password)", 1, revokedAt)))
    }

    fun shadow(
        id: String,
        desired: ShadowDocument = DefaultDesired.document,
        version: Long = 1,
        reportedVersion: Long? = null,
    ): Shadow {
        val sh = Shadow.initial(deviceId(id), desired, 1)
            .copy(desiredVersion = version, reportedVersion = reportedVersion)
        seed { it.copy(shadows = it.shadows + (sh.deviceId to sh)) }
        return sh
    }

    fun command(
        cmdId: String = "c-1",
        deviceId: String = "d-1",
        status: CommandStatus = CommandStatus.QUEUED,
        expiresAt: Long = clock.now + 300_000,
        createdAt: Long = clock.now,
        reason: String? = null,
    ): Command {
        val c = Command(
            cmdId, deviceId(deviceId), "ping", ShadowDocument.EMPTY, status, createdAt, expiresAt,
            null, null, null, reason,
        )
        seed { it.copy(commands = it.commands + (c.cmdId to c)) }
        return c
    }

    companion object {
        val INFO = DeviceInfo("Pixel", "Google", "16", 36)

        fun deviceId(raw: String): DeviceId = DeviceId.parse(raw) ?: error("bad id $raw")
    }
}
