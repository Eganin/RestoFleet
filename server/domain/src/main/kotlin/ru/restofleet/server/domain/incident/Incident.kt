package ru.restofleet.server.domain.incident

import ru.restofleet.server.domain.alert.RuleId
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.TimeRange
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.server.domain.restaurant.RestaurantId

/** Тип сводного инцидента (ТЗ 6.3, классификатор). [code] — значение на проводе и в БД. */
enum class IncidentKind(val code: String, val rule: RuleId) {
    RESTAURANT_NETWORK("restaurant_network", RuleId.R07),
    BACKEND_GLOBAL("backend_global", RuleId.R08),
    BACKEND_LOCAL("backend_local", RuleId.R09),
    ;

    companion object {
        fun fromCode(code: String?): IncidentKind? = entries.firstOrNull { it.code == code }
    }
}

/** Почему инцидент закрыт (реш. 412). */
enum class ResolveReason(val code: String) {
    /** Условие не выполнялось два тика подряд. */
    CLEARED("cleared"),

    /** Смена «локально ↔ глобально»: вместо него открыт другой. */
    SUPERSEDED("superseded"),
    ;

    companion object {
        fun fromCode(code: String?): ResolveReason? = entries.firstOrNull { it.code == code }
    }
}

/** Ресторан в окне агрегатора по цели инцидента (реш. 410, 411, 435). */
data class RestaurantEvidence(
    val restaurantId: RestaurantId,
    val devicesTotal: Int,
    val devicesOnline: Int,
    val targetOnline: Int,
    val targetDown: Int,
    val refOnline: Int,
    val refUp: Int,
    val errorClasses: Map<String, Int>,
)

/** Пороги классификатора на тике (реш. 409): `p` — %, `m` — `max(3, ⌈0,25·n⌉)`, `n` — ресторанов с данными. */
data class IncidentThresholds(val p: Int, val m: Int, val n: Int)

/** Доказательства классификации на последнем тике, где условие выполнялось. */
data class IncidentEvidence(
    val evaluatedAt: Long,
    val restaurants: List<RestaurantEvidence>,
    val otherRestaurantsOk: Int,
    val thresholds: IncidentThresholds,
)

/**
 * Сводный инцидент (FR-BE-35…37). [missedTicks] — сколько тиков подряд условие не выполняется (закрытие на втором,
 * реш. 412). Закрывается только автоматически; подтверждение — [ackBy], [ackAt], [comment].
 */
data class Incident(
    val id: String,
    val kind: IncidentKind,
    val target: String,
    val restaurants: List<RestaurantId>,
    val startedAt: Long,
    val evidence: IncidentEvidence,
    val resolvedAt: Long? = null,
    val resolveReason: ResolveReason? = null,
    val missedTicks: Int = 0,
    val ackBy: String? = null,
    val ackAt: Long? = null,
    val comment: String? = null,
) {
    val isOpen: Boolean get() = resolvedAt == null
}

/** Инцидент для выдачи: рестораны и число подавленных тревог. */
data class IncidentView(
    val incident: Incident,
    val restaurants: List<Restaurant>,
    val suppressedAlerts: Int,
)

/** Фильтр `GET /incidents`: [open] — открытые/закрытые; интервал инцидента пересекается с [range] (реш. 441). */
data class IncidentFilter(val open: Boolean? = null, val range: TimeRange = TimeRange())

/** Порт инцидентов. Только в транзакции. */
interface IncidentRepository {
    suspend fun insert(incident: Incident)

    suspend fun update(incident: Incident)

    suspend fun findForUpdate(id: String): Incident?

    suspend fun find(id: String): Incident?

    suspend fun open(): List<Incident>

    /** Новые сначала (`started_at`, затем `id`). */
    suspend fun list(filter: IncidentFilter, page: PageRequest): List<Incident>

    /** Сколько тревог (любого состояния) подавлено каждым инцидентом. */
    suspend fun suppressedCounts(ids: Collection<String>): Map<String, Int>
}

/** История агрегатора (`probe_agg_1m`, реш. 411): строка на минуту, ресторан и цель; классификатор её не читает. */
interface ProbeHistoryRepository {
    suspend fun record(bucket: Long, stats: List<RestaurantStats>)
}

/** Межпроцессная блокировка задачи на время транзакции (`pg_try_advisory_xact_lock`). */
fun interface JobLock {
    suspend fun tryLock(key: Long): Boolean
}
