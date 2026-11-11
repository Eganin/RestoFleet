package ru.restofleet.server.data.db

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import ru.restofleet.server.data.mapper.ShadowJson
import ru.restofleet.server.domain.shadow.ReportedShadow
import ru.restofleet.server.domain.shadow.Shadow
import ru.restofleet.server.domain.shadow.ShadowRepository
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.shadow.ShadowDocument

/** `shadows`: документы — `jsonb` через [ShadowJson]; `findForUpdate` — `SELECT … FOR UPDATE`. */
internal class ExposedShadowRepository : ShadowRepository {
    private val errorsSerializer = ListSerializer(String.serializer())

    override suspend fun insert(shadow: Shadow) {
        ShadowsTable.insert {
            it[deviceId] = shadow.deviceId.value
            it[desired] = ShadowJson.encode(shadow.desired)
            it[desiredVersion] = shadow.desiredVersion
            it[desiredUpdatedAt] = shadow.desiredUpdatedAt.toTimestamp()
            it[reported] = shadow.reported?.let(ShadowJson::encode)
            it[reportedVersion] = shadow.reportedVersion
            it[appliedAt] = shadow.appliedAt?.toTimestamp()
            it[reportedAt] = shadow.reportedAt?.toTimestamp()
            it[errors] = RfJson.encodeToString(errorsSerializer, shadow.errors)
        }
    }

    override suspend fun find(deviceId: DeviceId): Shadow? =
        ShadowsTable.selectAll().where { ShadowsTable.deviceId eq deviceId.value }.singleOrNull()?.toShadow()

    override suspend fun findForUpdate(deviceId: DeviceId): Shadow? = ShadowsTable.selectAll()
        .where { ShadowsTable.deviceId eq deviceId.value }
        .forUpdate(ForUpdateOption.ForUpdate)
        .singleOrNull()
        ?.toShadow()

    override suspend fun updateDesired(deviceId: DeviceId, desired: ShadowDocument, version: Long, at: Long) {
        ShadowsTable.update({ ShadowsTable.deviceId eq deviceId.value }) {
            it[ShadowsTable.desired] = ShadowJson.encode(desired)
            it[desiredVersion] = version
            it[desiredUpdatedAt] = at.toTimestamp()
        }
    }

    override suspend fun updateReported(report: ReportedShadow, at: Long) {
        ShadowsTable.update({ ShadowsTable.deviceId eq report.deviceId.value }) {
            it[reported] = ShadowJson.encode(report.document)
            it[reportedVersion] = report.appliedVersion
            it[appliedAt] = report.appliedAt.toTimestamp()
            it[reportedAt] = at.toTimestamp()
            it[errors] = RfJson.encodeToString(errorsSerializer, report.errors)
        }
    }

    private fun ResultRow.toShadow() = Shadow(
        deviceId = DeviceId.parse(this[ShadowsTable.deviceId]) ?: error("invalid device id in database"),
        desired = ShadowJson.decode(this[ShadowsTable.desired]),
        desiredVersion = this[ShadowsTable.desiredVersion],
        desiredUpdatedAt = this[ShadowsTable.desiredUpdatedAt].toMillis(),
        reported = this[ShadowsTable.reported]?.let(ShadowJson::decode),
        reportedVersion = this[ShadowsTable.reportedVersion],
        appliedAt = this[ShadowsTable.appliedAt]?.toMillis(),
        reportedAt = this[ShadowsTable.reportedAt]?.toMillis(),
        errors = RfJson.decodeFromString(errorsSerializer, this[ShadowsTable.errors]),
    )
}
