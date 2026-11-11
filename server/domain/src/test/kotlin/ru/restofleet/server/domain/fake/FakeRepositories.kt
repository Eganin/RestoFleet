package ru.restofleet.server.domain.fake

import ru.restofleet.server.domain.auth.RefreshToken
import ru.restofleet.server.domain.auth.RefreshTokenRepository
import ru.restofleet.server.domain.auth.User
import ru.restofleet.server.domain.auth.UserRepository
import ru.restofleet.server.domain.command.Command
import ru.restofleet.server.domain.command.CommandReasons
import ru.restofleet.server.domain.command.CommandRepository
import ru.restofleet.server.domain.command.CommandStatus
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.TimeRange
import ru.restofleet.server.domain.device.CredentialRepository
import ru.restofleet.server.domain.device.Device
import ru.restofleet.server.domain.device.DeviceAnnouncement
import ru.restofleet.server.domain.device.DeviceFilter
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.device.DeviceTransport
import ru.restofleet.server.domain.ingest.DeviceEvent
import ru.restofleet.server.domain.ingest.ProbeRecord
import ru.restofleet.server.domain.ingest.ProbeReport
import ru.restofleet.server.domain.ingest.TelemetryRepository
import ru.restofleet.server.domain.ingest.TelemetrySnapshot
import ru.restofleet.server.domain.presence.DeviceSession
import ru.restofleet.server.domain.presence.SessionRepository
import ru.restofleet.server.domain.provisioning.ProvisioningToken
import ru.restofleet.server.domain.provisioning.TokenRepository
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.server.domain.restaurant.RestaurantRepository
import ru.restofleet.server.domain.shadow.ReportedShadow
import ru.restofleet.server.domain.shadow.Shadow
import ru.restofleet.server.domain.shadow.ShadowRepository
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.shadow.ShadowDocument

class FakeRestaurantRepository(private val db: InMemoryDb) : RestaurantRepository {
    override suspend fun insert(restaurant: Restaurant) =
        db.update { it.copy(restaurants = it.restaurants + (restaurant.id to restaurant)) }

    override suspend fun update(restaurant: Restaurant) = insert(restaurant)

    override suspend fun findById(id: RestaurantId): Restaurant? = db.read { it.restaurants[id] }

    override suspend fun findByCode(code: String): Restaurant? =
        db.read { s -> s.restaurants.values.firstOrNull { it.code == code } }

    override suspend fun list(): List<Restaurant> = db.read { s -> s.restaurants.values.sortedBy { it.code } }
}

class FakeDeviceRepository(private val db: InMemoryDb) : DeviceRepository {
    override suspend fun insert(device: Device) = db.update { it.copy(devices = it.devices + (device.id to device)) }

    override suspend fun findById(id: DeviceId): Device? = db.read { it.devices[id] }

    override suspend fun list(filter: DeviceFilter, page: PageRequest): List<Device> = db.read { s ->
        s.devices.values
            .filter { filter.restaurantId == null || it.restaurantId == filter.restaurantId }
            .filter { filter.role == null || it.role == filter.role }
            .filter { filter.status == null || it.status == filter.status }
            .sortedWith(compareBy({ it.createdAt }, { it.id.value }))
            .drop(page.offset.toInt())
            .take(page.limit)
    }

    override suspend fun activeIds(ids: Collection<DeviceId>): Set<DeviceId> =
        db.read { s -> ids.filter { id -> s.devices[id]?.isDecommissioned == false }.toSet() }

    override suspend fun markOnline(
        id: DeviceId,
        at: Long,
        transport: DeviceTransport,
        announcement: DeviceAnnouncement,
    ) =
        change(id) {
            it.copy(
                status = DeviceStatus.ONLINE,
                lastSeenAt = at,
                transport = transport,
                agentVersion = announcement.agentVersion ?: it.agentVersion,
                mode = announcement.mode ?: it.mode,
                info = announcement.info ?: it.info,
            )
        }

    override suspend fun markOffline(id: DeviceId, at: Long) = change(id) { it.copy(status = DeviceStatus.OFFLINE) }

    override suspend fun touch(ids: Collection<DeviceId>, at: Long) =
        ids.forEach { id -> change(id) { it.copy(lastSeenAt = at) } }

    override suspend fun markStalePollersOffline(cutoff: Long): Int {
        val stale = db.read { s ->
            s.devices.values.filter {
                it.transport == DeviceTransport.POLL && it.status == DeviceStatus.ONLINE &&
                    (it.lastSeenAt ?: 0) < cutoff
            }
        }
        stale.forEach { d -> change(d.id) { it.copy(status = DeviceStatus.OFFLINE) } }
        return stale.size
    }

    override suspend fun decommission(id: DeviceId, at: Long) {
        db.update { s ->
            val d = s.devices.getValue(id)
            s.copy(devices = s.devices + (id to d.copy(status = DeviceStatus.DECOMMISSIONED, decommissionedAt = at)))
        }
    }

    private fun change(id: DeviceId, transform: (Device) -> Device) = db.update { s ->
        val d = s.devices[id]
        if (d == null || d.isDecommissioned) s else s.copy(devices = s.devices + (id to transform(d)))
    }
}

class FakeCredentialRepository(private val db: InMemoryDb) : CredentialRepository {
    override suspend fun insert(deviceId: DeviceId, passwordHash: String, createdAt: Long) =
        db.update {
            it.copy(credentials = it.credentials + (deviceId to StoredCredential(passwordHash, createdAt, null)))
        }

    override suspend fun findActiveHash(deviceId: DeviceId): String? =
        db.read { s -> s.credentials[deviceId]?.takeIf { it.revokedAt == null }?.hash }

    override suspend fun revoke(deviceId: DeviceId, at: Long) = db.update { s ->
        val c = s.credentials[deviceId] ?: return@update s
        s.copy(credentials = s.credentials + (deviceId to c.copy(revokedAt = at)))
    }
}

class FakeTokenRepository(private val db: InMemoryDb) : TokenRepository {
    override suspend fun insertAll(tokens: List<ProvisioningToken>) =
        db.update { s -> s.copy(tokens = s.tokens + tokens.associateBy { it.id }) }

    override suspend fun redeem(tokenHash: String, now: Long): ProvisioningToken? {
        val token = db.read { s ->
            s.tokens.values.firstOrNull { it.tokenHash == tokenHash && it.usedAt == null && it.expiresAt > now }
        } ?: return null
        val used = token.copy(usedAt = now)
        db.update { it.copy(tokens = it.tokens + (used.id to used)) }
        return used
    }

    override suspend fun findByHash(tokenHash: String): ProvisioningToken? =
        db.read { s -> s.tokens.values.firstOrNull { it.tokenHash == tokenHash } }

    override suspend fun attachDevice(tokenId: String, deviceId: DeviceId) = db.update { s ->
        s.copy(tokens = s.tokens + (tokenId to s.tokens.getValue(tokenId).copy(deviceId = deviceId)))
    }
}

class FakeSessionRepository(private val db: InMemoryDb) : SessionRepository {
    override suspend fun open(session: DeviceSession) = db.update { s ->
        if (s.sessions.any { it.deviceId == session.deviceId && it.connId == session.connId }) {
            s
        } else {
            s.copy(sessions = s.sessions + session)
        }
    }

    override suspend fun latest(deviceId: DeviceId): DeviceSession? =
        db.read { s -> s.sessions.filter { it.deviceId == deviceId }.maxByOrNull { it.connectedAt } }

    override suspend fun close(deviceId: DeviceId, connId: String, at: Long, reason: String) = db.update { s ->
        s.copy(
            sessions = s.sessions.map {
                if (it.deviceId == deviceId && it.connId == connId &&
                    it.isOpen) it.copy(disconnectedAt = at, reason = reason) else it
            },
        )
    }

    override suspend fun closeOpen(deviceId: DeviceId, at: Long, reason: String, exceptConnId: String?) =
        db.update { s ->
        s.copy(
            sessions = s.sessions.map {
                if (it.deviceId == deviceId && it.isOpen && it.connId != exceptConnId) {
                    it.copy(disconnectedAt = at, reason = reason)
                } else {
                    it
                }
            },
        )
    }
}

class FakeShadowRepository(private val db: InMemoryDb) : ShadowRepository {
    var lockedFor = mutableListOf<DeviceId>()

    override suspend fun insert(shadow: Shadow) =
        db.update { it.copy(shadows = it.shadows + (shadow.deviceId to shadow)) }

    override suspend fun find(deviceId: DeviceId): Shadow? = db.read { it.shadows[deviceId] }

    override suspend fun findForUpdate(deviceId: DeviceId): Shadow? = find(deviceId).also { lockedFor += deviceId }

    override suspend fun updateDesired(deviceId: DeviceId, desired: ShadowDocument, version: Long, at: Long) =
        db.update { s ->
            val sh = s.shadows.getValue(deviceId)
            val updated = sh.copy(desired = desired, desiredVersion = version, desiredUpdatedAt = at)
            s.copy(shadows = s.shadows + (deviceId to updated))
        }

    override suspend fun updateReported(report: ReportedShadow, at: Long) = db.update { s ->
        val sh = s.shadows.getValue(report.deviceId)
        val updated = sh.copy(
            reported = report.document,
            reportedVersion = report.appliedVersion,
            appliedAt = report.appliedAt,
            reportedAt = at,
            errors = report.errors,
        )
        s.copy(shadows = s.shadows + (report.deviceId to updated))
    }
}

class FakeTelemetryRepository(private val db: InMemoryDb) : TelemetryRepository {
    override suspend fun insertTelemetry(items: List<TelemetrySnapshot>): Int {
        var inserted = 0
        db.update { s ->
            val fresh = items.filter { t -> s.telemetry.none { it.deviceId == t.deviceId && it.ts == t.ts } }
                .distinctBy { it.deviceId to it.ts }
            inserted = fresh.size
            s.copy(telemetry = s.telemetry + fresh)
        }
        return inserted
    }

    override suspend fun insertProbes(reports: List<ProbeReport>): Int {
        var inserted = 0
        db.update { s ->
            val rows = reports.flatMap { r -> r.results.map { ProbeRecord(r.deviceId, r.ts, it) } }
                .filter { p -> s.probes.none { it.deviceId == p.deviceId && it.ts == p.ts &&
                    it.result.target == p.result.target } }
            inserted = rows.size
            s.copy(probes = s.probes + rows)
        }
        return inserted
    }

    override suspend fun insertEvents(events: List<DeviceEvent>): Int {
        db.update { it.copy(events = it.events + events) }
        return events.size
    }

    override suspend fun latest(deviceId: DeviceId): TelemetrySnapshot? =
        db.read { s -> s.telemetry.filter { it.deviceId == deviceId }.maxByOrNull { it.ts } }

    override suspend fun telemetry(deviceId: DeviceId, range: TimeRange, page: PageRequest): List<TelemetrySnapshot> =
        db.read { s -> s.telemetry.filter { it.deviceId == deviceId &&
            range.has(it.ts) }.sortedByDescending { it.ts }.page(page) }

    override suspend fun probes(
        deviceId: DeviceId,
        range: TimeRange,
        target: String?,
        page: PageRequest,
    ): List<ProbeRecord> =
        db.read { s ->
            s.probes.filter { it.deviceId == deviceId && range.has(it.ts) &&
                (target == null || it.result.target == target) }
                .sortedByDescending { it.ts }.page(page)
        }

    override suspend fun events(
        deviceId: DeviceId,
        range: TimeRange,
        type: String?,
        page: PageRequest,
    ): List<DeviceEvent> =
        db.read { s ->
            s.events.filter { it.deviceId == deviceId && range.has(it.ts) && (type == null || it.type == type) }
                .sortedByDescending { it.ts }.page(page)
        }

    private fun TimeRange.has(ts: Long) = (from == null || ts >= from) && (to == null || ts < to)

    private fun <T> List<T>.page(page: PageRequest) = drop(page.offset.toInt()).take(page.limit)
}

class FakeCommandRepository(private val db: InMemoryDb) : CommandRepository {
    override suspend fun insert(command: Command) =
        db.update { it.copy(commands = it.commands + (command.cmdId to command)) }

    override suspend fun find(cmdId: String): Command? = db.read { it.commands[cmdId] }

    override suspend fun findForUpdate(cmdId: String): Command? = find(cmdId)

    override suspend fun update(command: Command) = insert(command)

    override suspend fun expireDue(now: Long): Int {
        val due = db.read { s ->
            s.commands.values.filter {
                (it.status == CommandStatus.QUEUED || it.status == CommandStatus.DELIVERED) && it.expiresAt < now
            }
        }
        due.forEach {
            insert(it.copy(status = CommandStatus.EXPIRED, finishedAt = now, reason = CommandReasons.EXPIRED))
        }
        return due.size
    }

    override suspend fun failUnfinished(deviceId: DeviceId, reason: String, now: Long): Int {
        val open = db.read { s ->
            s.commands.values.filter {
                it.deviceId == deviceId && (it.status == CommandStatus.QUEUED || it.status == CommandStatus.DELIVERED)
            }
        }
        open.forEach { insert(it.copy(status = CommandStatus.FAILED, finishedAt = now, reason = reason)) }
        return open.size
    }

    override suspend fun deliverQueued(deviceId: DeviceId, now: Long): List<Command> {
        val queued = db.read { s ->
            s.commands.values.filter { it.deviceId == deviceId && it.status == CommandStatus.QUEUED &&
                it.expiresAt > now }
                .sortedBy { it.createdAt }
        }
        return queued.map { it.copy(status = CommandStatus.DELIVERED, deliveredAt = now).also { c -> insert(c) } }
    }
}

class FakeUserRepository(private val db: InMemoryDb) : UserRepository {
    override suspend fun insert(user: User) = db.update { it.copy(users = it.users + (user.id to user)) }

    override suspend fun findByLogin(login: String): User? =
        db.read { s -> s.users.values.firstOrNull { it.login == login } }

    override suspend fun findById(id: String): User? = db.read { it.users[id] }

    override suspend fun updatePassword(id: String, passwordHash: String) =
        db.update { s -> s.copy(users = s.users + (id to s.users.getValue(id).copy(passwordHash = passwordHash))) }
}

class FakeRefreshTokenRepository(private val db: InMemoryDb) : RefreshTokenRepository {
    override suspend fun insert(token: RefreshToken) =
        db.update { it.copy(refreshTokens = it.refreshTokens + (token.id to token)) }

    override suspend fun consume(tokenHash: String, now: Long): RefreshToken? {
        val token = db.read { s ->
            s.refreshTokens.values.firstOrNull { it.tokenHash == tokenHash && it.revokedAt == null &&
                it.expiresAt > now }
        } ?: return null
        val revoked = token.copy(revokedAt = now)
        insert(revoked)
        return revoked
    }

    override suspend fun findByHash(tokenHash: String): RefreshToken? =
        db.read { s -> s.refreshTokens.values.firstOrNull { it.tokenHash == tokenHash } }

    override suspend fun revokeAll(userId: String, now: Long): Int {
        val active = db.read { s -> s.refreshTokens.values.filter { it.userId == userId && it.revokedAt == null } }
        active.forEach { insert(it.copy(revokedAt = now)) }
        return active.size
    }
}
