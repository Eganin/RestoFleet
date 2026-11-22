package ru.restofleet.server.data.db

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import ru.restofleet.server.domain.alert.Alert
import ru.restofleet.server.domain.alert.AlertConditionRepository
import ru.restofleet.server.domain.alert.AlertCounts
import ru.restofleet.server.domain.alert.AlertFilter
import ru.restofleet.server.domain.alert.AlertRepository
import ru.restofleet.server.domain.alert.AlertState
import ru.restofleet.server.domain.alert.AlertSubject
import ru.restofleet.server.domain.alert.AlertView
import ru.restofleet.server.domain.alert.Condition
import ru.restofleet.server.domain.alert.ConditionKey
import ru.restofleet.server.domain.alert.RuleId
import ru.restofleet.server.domain.alert.Severity
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.incident.ProbeObservation
import ru.restofleet.server.domain.incident.ProbeStateRepository
import ru.restofleet.server.domain.ingest.ProbeReport
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.protocol.RfJson
import java.time.OffsetDateTime

private val detailsSerializer = MapSerializer(String.serializer(), String.serializer())

private fun Map<String, String>.toJson(): String = RfJson.encodeToString(detailsSerializer, this)

private fun String.toDetails(): Map<String, String> = RfJson.decodeFromString(detailsSerializer, this)

/**
 * `alerts` (реш. 413, 414): вставка — `ON CONFLICT DO NOTHING` (частичный уникальный индекс на незакрытый субъект
 * страхует от параллельной вставки), выдача — с join на устройство и ресторан.
 */
@Suppress("TooManyFunctions") // операции порта AlertRepository и два помощника выборки с join
internal class ExposedAlertRepository : AlertRepository {
    override suspend fun insert(alert: Alert) {
        AlertsTable.insertIgnore {
            it[id] = alert.id.toUuid()
            it[ruleId] = alert.rule.code
            it[severity] = alert.severity.code
            it[deviceId] = alert.deviceId.value
            it[restaurantId] = alert.restaurantId.value.toUuid()
            it[target] = alert.subject.target?.take(MAX_TARGET)
            it[openedAt] = alert.openedAt.toTimestamp()
            it[auto] = alert.auto
            it[details] = alert.details.toJson()
            it[state] = alert.state.code
            it[parentIncidentId] = alert.parentIncidentId?.toUuidOrNull()
        }
    }

    override suspend fun update(alert: Alert) {
        AlertsTable.update({ AlertsTable.id eq alert.id.toUuid() }) {
            it[state] = alert.state.code
            it[ackBy] = alert.ackBy
            it[ackAt] = alert.ackAt?.toTimestamp()
            it[resolvedAt] = alert.resolvedAt?.toTimestamp()
            it[resolvedBy] = alert.resolvedBy
            it[comment] = alert.comment
            it[auto] = alert.auto
            it[parentIncidentId] = alert.parentIncidentId?.toUuidOrNull()
            it[details] = alert.details.toJson()
        }
    }

    override suspend fun autoResolve(id: String, at: Long): Boolean {
        val uuid = id.toUuidOrNull() ?: return false
        return AlertsTable.update({ (AlertsTable.id eq uuid) and notResolved() }) {
            it[state] = AlertState.RESOLVED.code
            it[resolvedAt] = at.toTimestamp()
            it[auto] = true
        } == 1
    }

    override suspend fun findForUpdate(id: String): Alert? {
        val uuid = id.toUuidOrNull() ?: return null
        return AlertsTable.selectAll().where { AlertsTable.id eq uuid }.forUpdate(ForUpdateOption.ForUpdate)
            .singleOrNull()?.toAlert()
    }

    override suspend fun active(deviceIds: Collection<DeviceId>): List<Alert> {
        if (deviceIds.isEmpty()) return emptyList()
        return AlertsTable.selectAll()
            .where { (AlertsTable.deviceId inList deviceIds.map { it.value }) and notResolved() }
            .map { it.toAlert() }
    }

    override suspend fun list(filter: AlertFilter, page: PageRequest): List<AlertView> {
        val restaurant = filter.restaurantId?.let { r -> r.value.toUuidOrNull() ?: return emptyList() }
        val conditions = listOfNotNull(
            filter.state?.let { AlertsTable.state eq it.code },
            notResolved().takeIf { filter.activeOnly },
            restaurant?.let { AlertsTable.restaurantId eq it },
            filter.deviceId?.let { AlertsTable.deviceId eq it.value },
            filter.rule?.let { AlertsTable.ruleId eq it.code },
            AlertsTable.parentIncidentId.isNull().takeUnless { filter.includeSuppressed },
        )
        return joined().selectAll()
            .where(conditions.fold(Op.TRUE as Op<Boolean>) { acc, op -> acc and op })
            .orderBy(AlertsTable.openedAt to SortOrder.DESC, AlertsTable.id to SortOrder.DESC)
            .limit(page.limit)
            .offset(page.offset)
            .map { it.toView() }
    }

    override suspend fun views(ids: Collection<String>): List<AlertView> {
        val uuids = ids.mapNotNull { it.toUuidOrNull() }
        if (uuids.isEmpty()) return emptyList()
        val byId = joined().selectAll().where { AlertsTable.id inList uuids }.map { it.toView() }
            .associateBy { it.alert.id }
        return ids.mapNotNull { byId[it.lowercase()] }
    }

    override suspend fun activeCounts(): AlertCounts {
        val total = AlertsTable.id.count()
        val rows = AlertsTable.select(AlertsTable.severity, total)
            .where { notResolved() and AlertsTable.parentIncidentId.isNull() }
            .groupBy(AlertsTable.severity)
            .associate { it[AlertsTable.severity] to it[total].toInt() }
        return AlertCounts(active = rows.values.sum(), critical = rows[Severity.CRITICAL.code] ?: 0)
    }

    override suspend fun activeInRestaurants(restaurantIds: Collection<RestaurantId>): List<Alert> {
        val uuids = restaurantIds.mapNotNull { it.value.toUuidOrNull() }
        if (uuids.isEmpty()) return emptyList()
        return AlertsTable.selectAll().where { (AlertsTable.restaurantId inList uuids) and notResolved() }
            .map { it.toAlert() }
    }

    override suspend fun activeSuppressed(): List<Alert> = AlertsTable.selectAll()
        .where { AlertsTable.parentIncidentId.isNotNull() and notResolved() }
        .map { it.toAlert() }

    override suspend fun setParent(alertId: String, parentIncidentId: String?) {
        val uuid = alertId.toUuidOrNull() ?: return
        AlertsTable.update({ AlertsTable.id eq uuid }) {
            it[AlertsTable.parentIncidentId] = parentIncidentId?.toUuidOrNull()
        }
    }

    private fun joined() = AlertsTable
        .join(DevicesTable, JoinType.INNER, AlertsTable.deviceId, DevicesTable.id)
        .join(RestaurantsTable, JoinType.INNER, AlertsTable.restaurantId, RestaurantsTable.id)

    private fun ResultRow.toView() = AlertView(
        alert = toAlert(),
        deviceName = this[DevicesTable.name],
        restaurantCode = this[RestaurantsTable.code],
        restaurantName = this[RestaurantsTable.name],
    )
}

internal fun notResolved(): Op<Boolean> = AlertsTable.state neq AlertState.RESOLVED.code

private fun ResultRow.toAlert() = Alert(
    id = this[AlertsTable.id].toString(),
    subject = AlertSubject(
        rule = RuleId.fromCode(this[AlertsTable.ruleId]) ?: error("unknown rule in database"),
        deviceId = DeviceId.parse(this[AlertsTable.deviceId]) ?: error("invalid device id in database"),
        target = this[AlertsTable.target],
    ),
    restaurantId = RestaurantId(this[AlertsTable.restaurantId].toString()),
    state = AlertState.fromCode(this[AlertsTable.state]) ?: error("unknown alert state in database"),
    openedAt = this[AlertsTable.openedAt].toMillis(),
    ackBy = this[AlertsTable.ackBy],
    ackAt = this[AlertsTable.ackAt]?.toMillis(),
    resolvedAt = this[AlertsTable.resolvedAt]?.toMillis(),
    resolvedBy = this[AlertsTable.resolvedBy],
    comment = this[AlertsTable.comment],
    auto = this[AlertsTable.auto],
    parentIncidentId = this[AlertsTable.parentIncidentId]?.toString(),
    details = this[AlertsTable.details].toDetails(),
)

/** `alert_conditions`: строка — действующий факт; пустая цель хранится `''` (часть первичного ключа). */
internal class ExposedAlertConditionRepository : AlertConditionRepository {
    override suspend fun active(deviceIds: Collection<DeviceId>): List<Condition> {
        if (deviceIds.isEmpty()) return emptyList()
        return AlertConditionsTable.selectAll()
            .where { AlertConditionsTable.deviceId inList deviceIds.map { it.value } }
            .mapNotNull { row ->
                val rule = RuleId.fromCode(row[AlertConditionsTable.ruleId]) ?: return@mapNotNull null
                val key = ConditionKey(
                    rule = rule,
                    deviceId = DeviceId.parse(row[AlertConditionsTable.deviceId]) ?: error("invalid device id"),
                    target = row[AlertConditionsTable.target].ifEmpty { null },
                    facet = row[AlertConditionsTable.facet],
                )
                val details = row[AlertConditionsTable.details].toDetails()
                Condition(key, row[AlertConditionsTable.since].toMillis(), details)
            }
    }

    override suspend fun upsert(condition: Condition) {
        AlertConditionsTable.upsert {
            it[deviceId] = condition.key.deviceId.value
            it[ruleId] = condition.key.rule.code
            it[target] = condition.key.target.orEmpty().take(MAX_TARGET)
            it[facet] = condition.key.facet
            it[since] = condition.since.toTimestamp()
            it[details] = condition.details.toJson()
        }
    }

    override suspend fun delete(key: ConditionKey) {
        AlertConditionsTable.deleteWhere {
            (deviceId eq key.deviceId.value) and (ruleId eq key.rule.code) and
                (target eq key.target.orEmpty().take(MAX_TARGET)) and (facet eq key.facet)
        }
    }
}

/**
 * `probe_states` (реш. 411): состояние автомата K меняется только по `probe_state` (повтор того же состояния `since`
 * не сдвигает), время последнего результата — `GREATEST` из хранимого и нового.
 */
internal class ExposedProbeStateRepository : ProbeStateRepository {
    override suspend fun recordState(deviceId: DeviceId, target: String, down: Boolean, error: String?, at: Long) {
        val key = target.take(MAX_PROBE_TARGET)
        val state = if (down) DOWN else UP
        val current = ProbeStatesTable.select(ProbeStatesTable.state)
            .where { (ProbeStatesTable.deviceId eq deviceId.value) and (ProbeStatesTable.target eq key) }
            .forUpdate(ForUpdateOption.ForUpdate)
            .singleOrNull()
        when {
            current == null -> ProbeStatesTable.insert {
                it[ProbeStatesTable.deviceId] = deviceId.value
                it[ProbeStatesTable.target] = key
                it[ProbeStatesTable.state] = state
                it[since] = at.toTimestamp()
                it[ProbeStatesTable.error] = error?.take(MAX_ERROR)
            }
            else -> ProbeStatesTable.update({
                (ProbeStatesTable.deviceId eq deviceId.value) and (ProbeStatesTable.target eq key)
            }) {
                if (current[ProbeStatesTable.state] != state) {
                    it[ProbeStatesTable.state] = state
                    it[since] = at.toTimestamp()
                }
                it[ProbeStatesTable.error] = error?.take(MAX_ERROR)
            }
        }
    }

    override suspend fun recordResults(reports: List<ProbeReport>) {
        val latest = reports
            .flatMap { r -> r.results.map { (r.deviceId.value to it.target.take(MAX_PROBE_TARGET)) to r.ts } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, times) -> times.max() }
        if (latest.isEmpty()) return
        val column = ProbeStatesTable.lastResultAt
        ProbeStatesTable.batchUpsert(
            latest.entries.sortedBy { it.key.first + "/" + it.key.second },
            ProbeStatesTable.deviceId, ProbeStatesTable.target,
            onUpdate = {
                it[column] = CustomFunction<OffsetDateTime?>("GREATEST", column.columnType, column, insertValue(column))
            },
            shouldReturnGeneratedValues = false,
        ) { (pair, ts) ->
            this[ProbeStatesTable.deviceId] = pair.first
            this[ProbeStatesTable.target] = pair.second
            this[ProbeStatesTable.state] = UP
            this[ProbeStatesTable.since] = ts.toTimestamp()
            this[column] = ts.toTimestamp()
        }
    }

    override suspend fun fresh(since: Long): List<ProbeObservation> = ProbeStatesTable.selectAll()
        .where { ProbeStatesTable.lastResultAt greaterEq since.toTimestamp() }
        .map {
            ProbeObservation(
                deviceId = DeviceId.parse(it[ProbeStatesTable.deviceId]) ?: error("invalid device id in database"),
                target = it[ProbeStatesTable.target],
                down = it[ProbeStatesTable.state] == DOWN,
                error = it[ProbeStatesTable.error],
            )
        }

    private companion object {
        const val UP = "up"
        const val DOWN = "down"
        const val MAX_ERROR = 32
    }
}

private const val MAX_TARGET = 255
private const val MAX_PROBE_TARGET = 64
