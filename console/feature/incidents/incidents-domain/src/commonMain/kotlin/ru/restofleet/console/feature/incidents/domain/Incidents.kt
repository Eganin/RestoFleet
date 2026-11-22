package ru.restofleet.console.feature.incidents.domain

import kotlinx.coroutines.flow.Flow
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

/** Тип инцидента (FR-CN-10): сеть ресторана, общий отказ бэкенда, локальный отказ бэкенда. */
enum class IncidentKind(val wire: String) {
    RestaurantNetwork("restaurant_network"),
    BackendGlobal("backend_global"),
    BackendLocal("backend_local"),
    Unknown(""),
    ;

    companion object {
        fun fromWire(wire: String): IncidentKind = entries.firstOrNull { it.wire == wire && it != Unknown } ?: Unknown
    }
}

data class RestaurantRef(val id: String, val code: String, val name: String)

data class Incident(
    val id: String,
    val kind: IncidentKind,
    val ruleId: String,
    val resolved: Boolean,
    val target: String,
    val restaurants: List<RestaurantRef>,
    val startedAt: Long,
    val resolvedAt: Long?,
    val resolveReason: String?,
    val suppressedAlerts: Int,
    val ackBy: String?,
    val comment: String?,
)

data class RestaurantEvidence(
    val name: String,
    val devicesTotal: Int,
    val devicesOnline: Int,
    val targetOnline: Int,
    val targetDown: Int,
    val refOnline: Int,
    val refUp: Int,
    val errorClasses: Map<String, Int>,
)

/** Пороги классификатора: P — доля устройств с отказом (%), M — минимум ресторанов для «глобального», N — ресторанов в окне. */
data class Thresholds(val p: Int, val m: Int, val n: Int)

data class IncidentEvidence(
    val evaluatedAt: Long,
    val restaurants: List<RestaurantEvidence>,
    val otherRestaurantsOk: Int,
    val thresholds: Thresholds,
)

data class IncidentDetails(val incident: Incident, val evidence: IncidentEvidence)

/** Списки экрана: открытые и закрытые за последние сутки; [loadedAt] — момент загрузки. */
data class IncidentLists(val open: List<Incident>, val resolved: List<Incident>, val loadedAt: Long)

sealed interface IncidentsEvent {
    data class Pushed(val incident: Incident) : IncidentsEvent

    data object ReloadNeeded : IncidentsEvent
}

interface IncidentsRepository {
    suspend fun lists(): Outcome<IncidentLists, ConsoleError>

    suspend fun details(id: String): Outcome<IncidentDetails, ConsoleError>

    suspend fun acknowledge(id: String, comment: String?): Outcome<Incident, ConsoleError>

    fun events(): Flow<IncidentsEvent>
}
