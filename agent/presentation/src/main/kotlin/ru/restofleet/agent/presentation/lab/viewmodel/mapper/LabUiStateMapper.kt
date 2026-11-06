package ru.restofleet.agent.presentation.lab.viewmodel.mapper

import ru.restofleet.agent.domain.capability.CheckGroup
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.presentation.lab.viewmodel.Badge
import ru.restofleet.agent.presentation.lab.viewmodel.CheckRowUi
import ru.restofleet.agent.presentation.lab.viewmodel.LabUiState
import ru.restofleet.agent.presentation.lab.viewmodel.TabUi
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabFilter
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabSession
import ru.restofleet.agent.presentation.lab.viewmodel.model.SettingsTarget

/**
 * `LabSession -> LabUiState`. Чистый Kotlin без Android: подписи вкладок и шапки — константы здесь
 * (они состоят из числа и названия, которое в UI не локализуется), остальные тексты экрана — в ресурсах UI.
 */
class LabUiStateMapper {
    fun map(session: LabSession): LabUiState {
        val visible = CheckId.entries.filter { matches(it, session) }
        return LabUiState(
            header = session.device?.let { "$HEADER · API ${it.apiLevel}" } ?: HEADER,
            tabs = tabs(session),
            rows = visible.map { row(it, session) },
            isRunningAll = session.isRunningAll,
            isExporting = session.isExporting,
        )
    }

    private fun matches(id: CheckId, session: LabSession): Boolean = when (session.filter) {
        LabFilter.ALL -> true
        LabFilter.PROBLEMS -> session.results[id]?.status in PROBLEM_STATUSES
        LabFilter.DEVICE_OWNER -> id.group == CheckGroup.DEVICE_OWNER
    }

    private fun tabs(session: LabSession): List<TabUi> {
        val problems = session.results.values.count { it.status in PROBLEM_STATUSES }
        val deviceOwner = CheckId.entries.count { it.group == CheckGroup.DEVICE_OWNER }
        return listOf(
            TabUi(LabFilter.ALL, "Все · ${CheckId.entries.size}", session.filter == LabFilter.ALL),
            TabUi(LabFilter.PROBLEMS, "Проблемы · $problems", session.filter == LabFilter.PROBLEMS),
            TabUi(LabFilter.DEVICE_OWNER, "DO · $deviceOwner", session.filter == LabFilter.DEVICE_OWNER),
        )
    }

    private fun row(id: CheckId, session: LabSession): CheckRowUi {
        val result = session.results[id]
        return CheckRowUi(
            id = id.name,
            title = "${id.name} ${id.title}",
            badge = result?.let { badge(it.status) } ?: Badge.PENDING,
            summary = result?.let(::summary) ?: PENDING_SUMMARY,
            isRunning = id in session.running,
            canSend = result != null,
            settingsTargets = if (id == CheckId.L4) SettingsTarget.entries else emptyList(),
        )
    }

    private fun badge(status: CheckStatus): Badge = when (status) {
        CheckStatus.OK -> Badge.OK
        CheckStatus.LIMITED -> Badge.WARN
        CheckStatus.UNAVAILABLE -> Badge.FAIL
        CheckStatus.NOT_APPLICABLE, CheckStatus.NOT_CHECKED -> Badge.NA
    }

    private fun summary(result: CheckResult): String =
        result.note ?: result.raw.entries.joinToString(" · ") { (key, value) -> "$key $value" }

    private companion object {
        const val HEADER = "Capability Lab"
        const val PENDING_SUMMARY = "⏳ не запускалась"
        val PROBLEM_STATUSES = setOf(CheckStatus.LIMITED, CheckStatus.UNAVAILABLE)
    }
}
