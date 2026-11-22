package ru.restofleet.console.feature.dashboard.domain

import kotlinx.coroutines.flow.Flow
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

/** Сводка по парку (FR-CN-02). */
data class Summary(
    val devicesOnline: Int,
    val devicesTotal: Int,
    val devicesOffline: Int,
    val offlineLastHour: Int,
    val alertsOpen: Int,
    val alertsCritical: Int,
    val restaurantsWithProblems: Int,
    val restaurantsTotal: Int,
    val problems: List<ProblemRestaurant>,
)

/** Ресторан с проблемой: одна строка «Сокол · api недоступен · 4 из 6 устройств». */
data class ProblemRestaurant(
    val restaurantId: String,
    val code: String,
    val name: String,
    val ruleId: String,
    val count: Int,
    val devicesTotal: Int,
    val target: String?,
    val deviceId: String?,
    val deviceName: String?,
    val since: Long,
    val details: Map<String, String>,
)

/** Недавняя тревога для блока «Последние события». */
data class RecentAlert(
    val id: String,
    val ruleId: String,
    val critical: Boolean,
    val deviceId: String,
    val deviceName: String?,
    val restaurantName: String,
    val target: String?,
    val openedAt: Long,
    val details: Map<String, String>,
)

/** Всё, что показывает экран; [loadedAt] — момент загрузки, от него считаются «N мин назад». */
data class Dashboard(val summary: Summary, val recent: List<RecentAlert>, val loadedAt: Long)

/** Что пришло по realtime: готовая сводка или сигнал перечитать данные через REST. */
sealed interface DashboardEvent {
    data class SummaryPushed(val summary: Summary) : DashboardEvent

    data object ReloadNeeded : DashboardEvent
}

interface DashboardRepository {
    suspend fun load(): Outcome<Dashboard, ConsoleError>

    fun events(): Flow<DashboardEvent>
}

class LoadDashboard(private val repository: DashboardRepository) {
    suspend operator fun invoke(): Outcome<Dashboard, ConsoleError> = repository.load()
}

class ObserveDashboardEvents(private val repository: DashboardRepository) {
    operator fun invoke(): Flow<DashboardEvent> = repository.events()
}
