package ru.restofleet.server.data.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.incident.Incident
import ru.restofleet.server.domain.incident.IncidentEvidence
import ru.restofleet.server.domain.incident.IncidentFilter
import ru.restofleet.server.domain.incident.IncidentKind
import ru.restofleet.server.domain.incident.IncidentRepository
import ru.restofleet.server.domain.incident.IncidentThresholds
import ru.restofleet.server.domain.incident.JobLock
import ru.restofleet.server.domain.incident.ProbeHistoryRepository
import ru.restofleet.server.domain.incident.ResolveReason
import ru.restofleet.server.domain.incident.RestaurantEvidence
import ru.restofleet.server.domain.incident.RestaurantStats
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.protocol.RfJson

/** `incidents` (реш. 412, 452): рестораны и доказательства — jsonb, формат — классы ниже. */
internal class ExposedIncidentRepository : IncidentRepository {
    override suspend fun insert(incident: Incident) {
        IncidentsTable.insert {
            it[id] = incident.id.toUuid()
            it[kind] = incident.kind.code
            it[ruleId] = incident.kind.rule.code
            it[target] = incident.target.take(MAX_TARGET)
            it[startedAt] = incident.startedAt.toTimestamp()
            it.fill(incident)
        }
    }

    override suspend fun update(incident: Incident) {
        IncidentsTable.update({ IncidentsTable.id eq incident.id.toUuid() }) { it.fill(incident) }
    }

    override suspend fun updateEvaluation(incident: Incident) {
        IncidentsTable.update({ IncidentsTable.id eq incident.id.toUuid() }) { it.fillEvaluation(incident) }
    }

    override suspend fun findForUpdate(id: String): Incident? {
        val uuid = id.toUuidOrNull() ?: return null
        return IncidentsTable.selectAll().where { IncidentsTable.id eq uuid }.forUpdate(ForUpdateOption.ForUpdate)
            .singleOrNull()?.toIncident()
    }

    override suspend fun find(id: String): Incident? {
        val uuid = id.toUuidOrNull() ?: return null
        return IncidentsTable.selectAll().where { IncidentsTable.id eq uuid }.singleOrNull()?.toIncident()
    }

    override suspend fun open(): List<Incident> = IncidentsTable.selectAll()
        .where { IncidentsTable.resolvedAt.isNull() }
        .orderBy(IncidentsTable.startedAt to SortOrder.ASC)
        .map { it.toIncident() }

    override suspend fun list(filter: IncidentFilter, page: PageRequest): List<Incident> {
        val conditions = listOfNotNull(
            filter.open?.let { if (it) IncidentsTable.resolvedAt.isNull() else IncidentsTable.resolvedAt.isNotNull() },
            filter.range.to?.let { IncidentsTable.startedAt less it.toTimestamp() },
            filter.range.from?.let { from ->
                IncidentsTable.resolvedAt.isNull() or (IncidentsTable.resolvedAt greaterEq from.toTimestamp())
            },
        )
        return IncidentsTable.selectAll()
            .where(conditions.fold(Op.TRUE as Op<Boolean>) { acc, op -> acc and op })
            .orderBy(IncidentsTable.startedAt to SortOrder.DESC, IncidentsTable.id to SortOrder.DESC)
            .limit(page.limit)
            .offset(page.offset)
            .map { it.toIncident() }
    }

    override suspend fun suppressedCounts(ids: Collection<String>): Map<String, Int> {
        val uuids = ids.mapNotNull { it.toUuidOrNull() }
        if (uuids.isEmpty()) return emptyMap()
        val n = AlertsTable.id.count()
        return AlertsTable.select(AlertsTable.parentIncidentId, n)
            .where { AlertsTable.parentIncidentId inList uuids }
            .groupBy(AlertsTable.parentIncidentId)
            .associate { it[AlertsTable.parentIncidentId].toString() to it[n].toInt() }
    }

    private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.fill(incident: Incident) {
        fillEvaluation(incident)
        this[IncidentsTable.ackBy] = incident.ackBy
        this[IncidentsTable.ackAt] = incident.ackAt?.toTimestamp()
        this[IncidentsTable.comment] = incident.comment
    }

    /** Столбцы, которыми владеет тик (RV-5). */
    private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.fillEvaluation(incident: Incident) {
        this[IncidentsTable.restaurants] =
            RfJson.encodeToString(ids, incident.restaurants.map { it.value })
        this[IncidentsTable.resolvedAt] = incident.resolvedAt?.toTimestamp()
        this[IncidentsTable.resolveReason] = incident.resolveReason?.code
        this[IncidentsTable.missedTicks] = incident.missedTicks
        this[IncidentsTable.evidence] = RfJson.encodeToString(StoredEvidence.serializer(), incident.evidence.toStored())
    }

    private fun ResultRow.toIncident() = Incident(
        id = this[IncidentsTable.id].toString(),
        kind = IncidentKind.fromCode(this[IncidentsTable.kind]) ?: error("unknown incident kind in database"),
        target = this[IncidentsTable.target],
        restaurants = RfJson.decodeFromString(ids, this[IncidentsTable.restaurants]).map(::RestaurantId),
        startedAt = this[IncidentsTable.startedAt].toMillis(),
        evidence = RfJson.decodeFromString(StoredEvidence.serializer(), this[IncidentsTable.evidence]).toDomain(),
        resolvedAt = this[IncidentsTable.resolvedAt]?.toMillis(),
        resolveReason = ResolveReason.fromCode(this[IncidentsTable.resolveReason]),
        missedTicks = this[IncidentsTable.missedTicks],
        ackBy = this[IncidentsTable.ackBy],
        ackAt = this[IncidentsTable.ackAt]?.toMillis(),
        comment = this[IncidentsTable.comment],
    )

    private companion object {
        const val MAX_TARGET = 64
        val ids = ListSerializer(String.serializer())
    }
}

/** Формат `incidents.evidence` (внутренний, не контракт API). */
@Serializable
internal data class StoredEvidence(
    val evaluatedAt: Long,
    val restaurants: List<StoredRestaurantEvidence>,
    val otherRestaurantsOk: Int,
    val p: Int,
    val m: Int,
    val n: Int,
)

@Serializable
internal data class StoredRestaurantEvidence(
    val restaurantId: String,
    val devicesTotal: Int,
    val devicesOnline: Int,
    val targetOnline: Int,
    val targetDown: Int,
    val refOnline: Int,
    val refUp: Int,
    val errorClasses: Map<String, Int> = emptyMap(),
)

private fun IncidentEvidence.toStored() = StoredEvidence(
    evaluatedAt = evaluatedAt,
    restaurants = restaurants.map {
        StoredRestaurantEvidence(
            it.restaurantId.value, it.devicesTotal, it.devicesOnline, it.targetOnline, it.targetDown, it.refOnline,
            it.refUp, it.errorClasses,
        )
    },
    otherRestaurantsOk = otherRestaurantsOk,
    p = thresholds.p,
    m = thresholds.m,
    n = thresholds.n,
)

private fun StoredEvidence.toDomain() = IncidentEvidence(
    evaluatedAt = evaluatedAt,
    restaurants = restaurants.map {
        RestaurantEvidence(
            RestaurantId(it.restaurantId), it.devicesTotal, it.devicesOnline, it.targetOnline, it.targetDown,
            it.refOnline, it.refUp, it.errorClasses,
        )
    },
    otherRestaurantsOk = otherRestaurantsOk,
    thresholds = IncidentThresholds(p, m, n),
)

/** `probe_agg_1m` (реш. 411): строка на минуту, ресторан и цель; повтор в той же минуте перезаписывает. */
internal class ExposedProbeHistoryRepository(private val refTarget: String = "ref") : ProbeHistoryRepository {
    override suspend fun record(bucket: Long, stats: List<RestaurantStats>) {
        val rows = stats.flatMap { s -> s.targets.map { (target, t) -> Triple(s, target, t) } }
            .filter { it.first.restaurantId.value.toUuidOrNull() != null }
        if (rows.isEmpty()) return
        ProbeAgg1mTable.batchUpsert(
            rows, ProbeAgg1mTable.restaurantId, ProbeAgg1mTable.target, ProbeAgg1mTable.bucket,
            shouldReturnGeneratedValues = false,
        ) { (s, target, t) ->
            this[ProbeAgg1mTable.restaurantId] = s.restaurantId.value.toUuid()
            this[ProbeAgg1mTable.target] = target.take(MAX_TARGET)
            this[ProbeAgg1mTable.bucket] = bucket.toTimestamp()
            this[ProbeAgg1mTable.devicesTotal] = s.devicesTotal
            this[ProbeAgg1mTable.devicesOnline] = s.devicesOnline
            this[ProbeAgg1mTable.targetOnline] = t.online
            this[ProbeAgg1mTable.targetDown] = t.down
            this[ProbeAgg1mTable.refDown] = s.target(refTarget).down
        }
    }

    private companion object {
        const val MAX_TARGET = 64
    }
}

/** `pg_try_advisory_xact_lock`: снимается при завершении транзакции. Ключ — константа задачи (не ввод). */
internal class PostgresJobLock : JobLock {
    override suspend fun tryLock(key: Long): Boolean = TransactionManager.current()
        .exec("SELECT pg_try_advisory_xact_lock($key)") { rs -> rs.next() && rs.getBoolean(1) }
        ?: false
}
