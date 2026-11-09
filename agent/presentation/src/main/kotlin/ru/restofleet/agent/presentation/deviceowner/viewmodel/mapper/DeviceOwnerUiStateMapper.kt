package ru.restofleet.agent.presentation.deviceowner.viewmodel.mapper

import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.presentation.common.Badge
import ru.restofleet.agent.presentation.common.summaryText
import ru.restofleet.agent.presentation.common.toBadge
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerRowUi
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerUiState
import ru.restofleet.agent.presentation.deviceowner.viewmodel.model.DEVICE_OWNER_ONLY
import ru.restofleet.agent.presentation.deviceowner.viewmodel.model.DEVICE_OWNER_ROWS
import ru.restofleet.agent.presentation.deviceowner.viewmodel.model.DeviceOwnerSession
import ru.restofleet.agent.presentation.deviceowner.viewmodel.model.canRun
import ru.restofleet.agent.presentation.deviceowner.viewmodel.model.canRunAll

/**
 * `DeviceOwnerSession -> DeviceOwnerUiState`. Чистый Kotlin без Android: заголовок и тексты строк — константы
 * здесь (как в Lab), подписи виджетов — в ресурсах UI. Без роли Device Owner строки [DEVICE_OWNER_ONLY] — NA.
 */
class DeviceOwnerUiStateMapper {
    fun map(session: DeviceOwnerSession): DeviceOwnerUiState {
        val mode = session.device?.mode
        return DeviceOwnerUiState(
            title = when (mode) {
                AgentMode.DEVICE_OWNER -> TITLE_ACTIVE
                AgentMode.NORMAL -> TITLE_NORMAL
                null -> TITLE_UNKNOWN
            },
            isDeviceOwner = mode == AgentMode.DEVICE_OWNER,
            owner = if (mode == AgentMode.DEVICE_OWNER) OWNER else NO_VALUE,
            serial = session.results[CheckId.R13]?.raw?.get(SERIAL_KEY) ?: NO_VALUE,
            rows = DEVICE_OWNER_ROWS.map { row(it, session, mode) },
            apkUrl = session.apkUrl,
            apkSha256 = session.apkSha256,
            canFetchHash = session.apkUrl.isNotBlank() && !session.isFetchingHash,
            isFetchingHash = session.isFetchingHash,
            targetPackage = session.targetPackage,
            targetUrl = session.targetUrl,
            isRunningAll = session.isRunningAll,
            canRunAll = session.canRunAll,
        )
    }

    private fun row(id: CheckId, session: DeviceOwnerSession, mode: AgentMode?): DeviceOwnerRowUi {
        val result = session.results[id]
        val isRunning = id in session.running
        val notApplicable = id in DEVICE_OWNER_ONLY && mode == AgentMode.NORMAL
        return DeviceOwnerRowUi(
            id = id.name,
            title = "${id.name} ${id.title}",
            badge = if (notApplicable) Badge.NA else result?.status?.toBadge() ?: Badge.PENDING,
            summary = when {
                notApplicable -> NOT_APPLICABLE_TEXT
                else -> result?.let { summary(id, it) } ?: PENDING_SUMMARY
            },
            isRunning = isRunning,
            enabled = session.canRun(id),
            hasDelayedVariant = id == CheckId.D5,
        )
    }

    /** D6: коды `not_granted:<имена>` и `nothing_to_grant` из domain — в русский текст, остальное как есть. */
    private fun summary(id: CheckId, result: CheckResult): String {
        val note = result.note
        return when {
            id == CheckId.D6 && note == NOTHING_TO_GRANT -> NOTHING_TO_GRANT_TEXT
            id == CheckId.D6 && note != null && note.startsWith(NOT_GRANTED_PREFIX) ->
                "не выдано: " + note.removePrefix(NOT_GRANTED_PREFIX).split(",").joinToString(", ")
            else -> result.summaryText()
        }
    }

    private companion object {
        const val NOT_APPLICABLE_TEXT = "только в режиме Device Owner"
        const val TITLE_ACTIVE = "Device Owner активен"
        const val TITLE_NORMAL = "Обычный режим"
        const val TITLE_UNKNOWN = "Проверка режима…"
        const val OWNER = "ru.restofleet.agent"
        const val SERIAL_KEY = "serial"
        const val NO_VALUE = "—"
        const val PENDING_SUMMARY = "⏳ не запускалась"
        const val NOT_GRANTED_PREFIX = "not_granted:"
        const val NOTHING_TO_GRANT = "nothing_to_grant"
        const val NOTHING_TO_GRANT_TEXT = "нет разрешений для выдачи"
    }
}
