package ru.restofleet.agent.presentation.status.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.agent.presentation.common.Badge
import ru.restofleet.agent.presentation.common.InfoRow
import ru.restofleet.agent.presentation.common.PermissionRow
import ru.restofleet.agent.presentation.common.StringSpec

/**
 * Экран статуса (FR-AG-05). [connectionBadge] и [connection] — состояние сессии, [connectionDetails] — причина
 * ошибки, `connId`, брокер; [registrationNote] — вместо строк регистрации, если учётки нет или она недоступна.
 */
@Immutable
data class StatusUiState(
    val isLoading: Boolean,
    val isRefreshing: Boolean,
    val connection: StringSpec,
    val connectionBadge: Badge,
    val connectionDetails: List<InfoRow>,
    val registration: List<InfoRow>,
    val registrationNote: StringSpec?,
    val device: List<InfoRow>,
    val permissions: List<PermissionRow>,
    val probes: List<ProbeRow>,
    val labAvailable: Boolean,
)

/** Последний результат цели проверки: `up`/`down`/нет данных и короткая деталь (HTTP-код и время или ошибка). */
@Immutable
data class ProbeRow(val id: String, val state: StringSpec, val badge: Badge, val detail: StringSpec)
