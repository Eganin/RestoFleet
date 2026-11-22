package ru.restofleet.agent.presentation.status.viewmodel.mapper

import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.model.AgentStatus
import ru.restofleet.agent.domain.model.RegistrationState
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.agent.domain.probe.ProbeStatus
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.Badge
import ru.restofleet.agent.presentation.common.InfoRow
import ru.restofleet.agent.presentation.common.StringSpec
import ru.restofleet.agent.presentation.common.permissionRows
import ru.restofleet.agent.presentation.common.raw
import ru.restofleet.agent.presentation.common.toRows
import ru.restofleet.agent.presentation.status.viewmodel.ProbeRow
import ru.restofleet.agent.presentation.status.viewmodel.StatusUiState
import ru.restofleet.agent.presentation.status.viewmodel.model.StatusSession
import ru.restofleet.shared.probes.ProbeState

/** Снимок статуса → UI. Пока снимка нет — «загрузка», поля пустые. Неизвестные (`null`) значения — прочерк. */
class StatusUiStateMapper {
    fun map(session: StatusSession): StatusUiState {
        val status = session.status
        return StatusUiState(
            isLoading = status == null,
            isRefreshing = session.isRefreshing,
            connection = StringSpec(status?.connection?.state.label()),
            connectionBadge = status?.connection?.state.badge(),
            connectionDetails = status?.let(::connectionDetails).orEmpty(),
            registration = (status?.registration as? RegistrationState.Registered)?.registration?.toRows().orEmpty(),
            registrationNote = status?.registration?.note(),
            device = status?.let(::deviceRows).orEmpty(),
            permissions = permissionRows(status?.granted),
            probes = status?.probes.orEmpty().map(::probeRow),
            labAvailable = session.labAvailable,
        )
    }

    private fun connectionDetails(status: AgentStatus): List<InfoRow> = buildList {
        status.connection.errorCode?.let {
            add(InfoRow(StringSpec(R.string.status_section_connection), StringSpec(R.string.status_error, listOf(it))))
        }
        status.connection.connId?.let { add(InfoRow(StringSpec(R.string.status_conn_id), raw(it))) }
        status.connection.endpoint?.let { add(InfoRow(StringSpec(R.string.label_broker), raw(it))) }
    }

    private fun deviceRows(status: AgentStatus): List<InfoRow> = listOf(
        InfoRow(StringSpec(R.string.status_mode), status.mode.label()),
        InfoRow(StringSpec(R.string.status_outbox), status.outboxSize?.let { raw(it.toString()) } ?: none()),
        InfoRow(StringSpec(R.string.status_version), status.agentVersion?.let(::raw) ?: none()),
    )

    private fun probeRow(p: ProbeStatus): ProbeRow {
        val (state, badge) = when (p.state) {
            ProbeState.UP -> R.string.status_probe_up to Badge.OK
            ProbeState.DOWN -> R.string.status_probe_down to Badge.FAIL
            ProbeState.UNKNOWN -> R.string.status_probe_unknown to Badge.NA
        }
        val last = p.last
        val detail = if (last.ok) {
            StringSpec(R.string.status_probe_detail_ok, listOf(last.httpStatus?.toString() ?: "—", "${last.totalMs}"))
        } else {
            StringSpec(
                R.string.status_probe_detail_fail,
                listOf(last.error?.code ?: last.httpStatus?.toString() ?: "—", "${p.consecutiveFailures}"),
            )
        }
        return ProbeRow(p.targetId, StringSpec(state), badge, detail)
    }

    private fun none() = StringSpec(R.string.value_none)

    private fun SessionState?.label(): Int = when (this) {
        SessionState.CONNECTED -> R.string.status_connected
        SessionState.CONNECTING -> R.string.status_connecting
        SessionState.DISCONNECTED, null -> R.string.status_disconnected
    }

    private fun SessionState?.badge(): Badge = when (this) {
        SessionState.CONNECTED -> Badge.OK
        SessionState.CONNECTING -> Badge.PENDING
        SessionState.DISCONNECTED -> Badge.FAIL
        null -> Badge.NA
    }

    private fun AgentMode?.label(): StringSpec = when (this) {
        AgentMode.NORMAL -> StringSpec(R.string.status_mode_normal)
        AgentMode.DEVICE_OWNER -> StringSpec(R.string.status_mode_device_owner)
        null -> none()
    }

    private fun RegistrationState.note(): StringSpec? = when (this) {
        is RegistrationState.Registered -> null
        RegistrationState.NotRegistered -> StringSpec(R.string.status_not_registered)
        RegistrationState.Unavailable -> StringSpec(R.string.status_registration_unavailable)
    }
}
