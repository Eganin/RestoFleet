package ru.restofleet.server.domain.alert

import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.DeviceId

/** Встроенные правила (ТЗ 6.3). [code] — значение на проводе и в БД (`R-01`, реш. 433). */
enum class RuleId(val code: String, val severity: Severity) {
    R01("R-01", Severity.WARNING),
    R02("R-02", Severity.WARNING),
    R03("R-03", Severity.CRITICAL),
    R04("R-04", Severity.WARNING),
    R05("R-05", Severity.CRITICAL),
    R06("R-06", Severity.WARNING),
    R07("R-07", Severity.CRITICAL),
    R08("R-08", Severity.CRITICAL),
    R09("R-09", Severity.CRITICAL),
    ;

    companion object {
        /** Правила тревог устройств; R-07…R-09 — сводные инциденты. */
        val ALERT_RULES: List<RuleId> = listOf(R01, R02, R03, R04, R05, R06)

        fun fromCode(code: String?): RuleId? = entries.firstOrNull { it.code == code }
    }
}

enum class Severity(val code: String) {
    CRITICAL("critical"),
    WARNING("warning"),
}

/** Жизненный цикл (FR-BE-31): `open → acknowledged → resolved`. */
enum class AlertState(val code: String) {
    OPEN("open"),
    ACKNOWLEDGED("acknowledged"),
    RESOLVED("resolved"),
    ;

    companion object {
        fun fromCode(code: String?): AlertState? = entries.firstOrNull { it.code == code }
    }
}

/** Субъект тревоги: незакрытая тревога на субъект одна (реш. 413). [target] — цель у R-05, пакет у R-04. */
data class AlertSubject(val rule: RuleId, val deviceId: DeviceId, val target: String? = null)

/** Тревога устройства (FR-BE-30, 31). [details] — значения условия строками (реш. 434). */
data class Alert(
    val id: String,
    val subject: AlertSubject,
    val restaurantId: RestaurantId,
    val state: AlertState,
    val openedAt: Long,
    val ackBy: String? = null,
    val ackAt: Long? = null,
    val resolvedAt: Long? = null,
    val resolvedBy: String? = null,
    val comment: String? = null,
    val auto: Boolean = false,
    val parentIncidentId: String? = null,
    val details: Map<String, String> = emptyMap(),
) {
    val rule: RuleId get() = subject.rule
    val deviceId: DeviceId get() = subject.deviceId
    val severity: Severity get() = subject.rule.severity
    val isActive: Boolean get() = state != AlertState.RESOLVED
}

/** Тревога для выдачи: с именем устройства и ресторана (join в репозитории). */
data class AlertView(
    val alert: Alert,
    val deviceName: String?,
    val restaurantCode: String,
    val restaurantName: String,
)

/** Фильтр `GET /alerts`; `null` — без условия. [activeOnly] — открытые и подтверждённые. */
data class AlertFilter(
    val state: AlertState? = null,
    val restaurantId: RestaurantId? = null,
    val deviceId: DeviceId? = null,
    val rule: RuleId? = null,
    val includeSuppressed: Boolean = false,
    val activeOnly: Boolean = false,
)

/**
 * Факт-условие устройства (реш. 443): часть условия правила, которая держится, пока не придёт обратный факт.
 * [facet] различает факты одного правила (R-03: `temp_high` и `thermal`; R-02: `battery_low` и `charging`).
 */
data class ConditionKey(val rule: RuleId, val deviceId: DeviceId, val target: String?, val facet: String) {
    val subject: AlertSubject get() = AlertSubject(rule, deviceId, target)
}

/** Действующий факт-условие: с какого момента и с какими значениями. */
data class Condition(val key: ConditionKey, val since: Long, val details: Map<String, String>)

/** Пороги, которые проверяет бэкенд (реш. 415): R-06 по телеметрии; остальные — у агента. */
data class RuleParams(
    val freeMbEnter: Long = DEFAULT_FREE_MB_ENTER,
    val freeMbExit: Long = DEFAULT_FREE_MB_EXIT,
) {
    init {
        require(freeMbExit >= freeMbEnter) { "freeMbExit must be >= freeMbEnter" }
    }

    companion object {
        const val DEFAULT_FREE_MB_ENTER = 500L
        const val DEFAULT_FREE_MB_EXIT = 600L
    }
}

/** Порт тревог. Только в транзакции. Незакрытая на субъект — одна (частичный уникальный индекс). */
interface AlertRepository {
    suspend fun insert(alert: Alert)

    suspend fun update(alert: Alert)

    /** С блокировкой строки до конца транзакции. */
    suspend fun findForUpdate(id: String): Alert?

    /** Незакрытые тревоги устройств, включая подавленные. */
    suspend fun active(deviceIds: Collection<DeviceId>): List<Alert>

    /** Новые сначала (`openedAt`, затем `id`). */
    suspend fun list(filter: AlertFilter, page: PageRequest): List<AlertView>

    suspend fun views(ids: Collection<String>): List<AlertView>

    /** Незакрытые неподавленные: всего и критичных. */
    suspend fun activeCounts(): AlertCounts

    /** Незакрытые тревоги устройств ресторанов (кандидаты в подавление). */
    suspend fun activeInRestaurants(restaurantIds: Collection<RestaurantId>): List<Alert>

    /** Незакрытые подавленные (с родителем). */
    suspend fun activeSuppressed(): List<Alert>

    suspend fun setParent(alertId: String, parentIncidentId: String?)
}

data class AlertCounts(val active: Int, val critical: Int)

/** Порт фактов-условий (`alert_conditions`): хранятся только действующие. Только в транзакции. */
interface AlertConditionRepository {
    suspend fun active(deviceIds: Collection<DeviceId>): List<Condition>

    suspend fun upsert(condition: Condition)

    suspend fun delete(key: ConditionKey)
}

/** Порядок незакрытых тревог в выдаче: сначала критичные, затем новые. */
val activeOrder: Comparator<Alert> =
    compareBy<Alert> { it.severity != Severity.CRITICAL }.thenByDescending { it.openedAt }.thenBy { it.id }
