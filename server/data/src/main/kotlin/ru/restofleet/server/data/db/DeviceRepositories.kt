package ru.restofleet.server.data.db

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.device.CredentialRepository
import ru.restofleet.server.domain.device.Device
import ru.restofleet.server.domain.device.DeviceAnnouncement
import ru.restofleet.server.domain.device.DeviceFilter
import ru.restofleet.server.domain.device.DeviceInfo
import ru.restofleet.server.domain.device.DeviceRepository
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.device.DeviceTransport
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.DeviceId

/** `devices`: атомарные изменения статуса; выведенное из эксплуатации устройство не трогают (кроме [decommission]). */
internal class ExposedDeviceRepository : DeviceRepository {
    override suspend fun insert(device: Device) {
        DevicesTable.insert {
            it[id] = device.id.value
            it[restaurantId] = device.restaurantId.value.toUuid()
            it[role] = device.role.code
            it[name] = device.name
            it[status] = device.status.code
            it[transport] = device.transport?.code
            it[lastSeenAt] = device.lastSeenAt?.toTimestamp()
            it[decommissionedAt] = device.decommissionedAt?.toTimestamp()
            it[createdAt] = device.createdAt.toTimestamp()
            it.info(device.info)
            it[agentVersion] = device.agentVersion
            it[mode] = device.mode
        }
    }

    override suspend fun findById(id: DeviceId): Device? =
        DevicesTable.selectAll().where { DevicesTable.id eq id.value }.singleOrNull()?.toDevice()

    override suspend fun list(filter: DeviceFilter, page: PageRequest): List<Device> {
        val conditions = listOfNotNull(
            filter.restaurantId?.let { r ->
                r.value.toUuidOrNull()?.let { DevicesTable.restaurantId eq it } ?: return emptyList()
            },
            filter.role?.let { DevicesTable.role eq it.code },
            filter.status?.let { DevicesTable.status eq it.code },
        )
        return DevicesTable.selectAll()
            .where(conditions.fold(Op.TRUE as Op<Boolean>) { acc, op -> acc and op })
            .orderBy(DevicesTable.createdAt to SortOrder.ASC, DevicesTable.id to SortOrder.ASC)
            .limit(page.limit)
            .offset(page.offset)
            .map { it.toDevice() }
    }

    override suspend fun activeIds(ids: Collection<DeviceId>): Set<DeviceId> {
        if (ids.isEmpty()) return emptySet()
        return DevicesTable.select(DevicesTable.id)
            .where { (DevicesTable.id inList ids.map { it.value }) and notDecommissioned() }
            .mapNotNull { DeviceId.parse(it[DevicesTable.id]) }
            .toSet()
    }

    override suspend fun markOnline(
        id: DeviceId,
        at: Long,
        transport: DeviceTransport,
        announcement: DeviceAnnouncement,
    ) {
        change(id) {
            it[status] = DeviceStatus.ONLINE.code
            it[lastSeenAt] = at.toTimestamp()
            it[DevicesTable.transport] = transport.code
            announcement.agentVersion?.let { v -> it[agentVersion] = v.take(MAX_VERSION) }
            announcement.mode?.let { m -> it[mode] = m.take(MAX_MODE) }
            announcement.info?.let { info -> it.info(info) }
        }
    }

    override suspend fun markOffline(id: DeviceId, at: Long) {
        change(id) { it[status] = DeviceStatus.OFFLINE.code }
    }

    override suspend fun touch(ids: Collection<DeviceId>, at: Long) {
        if (ids.isEmpty()) return
        DevicesTable.update({ (DevicesTable.id inList ids.map { it.value }) and notDecommissioned() }) {
            it[lastSeenAt] = at.toTimestamp()
        }
    }

    override suspend fun markStalePollersOffline(cutoff: Long): Int = DevicesTable.update({
        (DevicesTable.transport eq DeviceTransport.POLL.code) and
            (DevicesTable.status eq DeviceStatus.ONLINE.code) and
            (DevicesTable.lastSeenAt less cutoff.toTimestamp())
    }) { it[status] = DeviceStatus.OFFLINE.code }

    override suspend fun decommission(id: DeviceId, at: Long) {
        DevicesTable.update({ DevicesTable.id eq id.value }) {
            it[status] = DeviceStatus.DECOMMISSIONED.code
            it[decommissionedAt] = at.toTimestamp()
        }
    }
}

private const val MAX_VERSION = 64
private const val MAX_MODE = 32
private const val MAX_MODEL = 100
private const val MAX_OS = 32

/** Изменение строки устройства, если оно не выведено из эксплуатации. */
private fun change(id: DeviceId, body: DevicesTable.(UpdateStatement) -> Unit) {
    DevicesTable.update({ (DevicesTable.id eq id.value) and notDecommissioned() }, body = body)
}

private fun notDecommissioned(): Op<Boolean> = DevicesTable.status neq DeviceStatus.DECOMMISSIONED.code

private fun UpdateBuilder<*>.info(info: DeviceInfo?) {
    this[DevicesTable.model] = info?.model?.take(MAX_MODEL)
    this[DevicesTable.manufacturer] = info?.manufacturer?.take(MAX_MODEL)
    this[DevicesTable.osVersion] = info?.osVersion?.take(MAX_OS)
    this[DevicesTable.apiLevel] = info?.apiLevel
}

private fun ResultRow.toDevice(): Device {
    val model = this[DevicesTable.model]
    val info = model?.let {
        DeviceInfo(
            model = it,
            manufacturer = this[DevicesTable.manufacturer].orEmpty(),
            osVersion = this[DevicesTable.osVersion].orEmpty(),
            apiLevel = this[DevicesTable.apiLevel] ?: 0,
        )
    }
    return Device(
        id = DeviceId.parse(this[DevicesTable.id]) ?: error("invalid device id in database"),
        restaurantId = RestaurantId(this[DevicesTable.restaurantId].toString()),
        role = DeviceRole.fromCode(this[DevicesTable.role]) ?: error("unknown role in database"),
        name = this[DevicesTable.name],
        status = DeviceStatus.fromCode(this[DevicesTable.status]) ?: error("unknown status in database"),
        transport = DeviceTransport.fromCode(this[DevicesTable.transport]),
        lastSeenAt = this[DevicesTable.lastSeenAt]?.toMillis(),
        decommissionedAt = this[DevicesTable.decommissionedAt]?.toMillis(),
        createdAt = this[DevicesTable.createdAt].toMillis(),
        info = info,
        agentVersion = this[DevicesTable.agentVersion],
        mode = this[DevicesTable.mode],
    )
}

/** `device_credentials`. */
internal class ExposedCredentialRepository : CredentialRepository {
    override suspend fun insert(deviceId: DeviceId, passwordHash: String, createdAt: Long) {
        DeviceCredentialsTable.insert {
            it[DeviceCredentialsTable.deviceId] = deviceId.value
            it[DeviceCredentialsTable.passwordHash] = passwordHash
            it[DeviceCredentialsTable.createdAt] = createdAt.toTimestamp()
        }
    }

    override suspend fun findActiveHash(deviceId: DeviceId): String? = DeviceCredentialsTable
        .select(DeviceCredentialsTable.passwordHash, DeviceCredentialsTable.revokedAt)
        .where { DeviceCredentialsTable.deviceId eq deviceId.value }
        .singleOrNull()
        ?.takeIf { it[DeviceCredentialsTable.revokedAt] == null }
        ?.get(DeviceCredentialsTable.passwordHash)

    override suspend fun revoke(deviceId: DeviceId, at: Long) {
        DeviceCredentialsTable.update({ DeviceCredentialsTable.deviceId eq deviceId.value }) {
            it[revokedAt] = at.toTimestamp()
        }
    }
}
