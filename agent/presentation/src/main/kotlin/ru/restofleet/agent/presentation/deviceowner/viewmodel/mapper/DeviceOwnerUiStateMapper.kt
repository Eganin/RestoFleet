package ru.restofleet.agent.presentation.deviceowner.viewmodel.mapper

import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.presentation.common.Badge
import ru.restofleet.agent.presentation.common.summaryText
import ru.restofleet.agent.presentation.common.toBadge
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerRowUi
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerUiState
import ru.restofleet.agent.presentation.deviceowner.viewmodel.model.DeviceOwnerSession

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
            owner = OWNER,
            serial = session.results[CheckId.R13]?.raw?.get(SERIAL_KEY) ?: NO_VALUE,
            rows = ROWS.map { row(it, session, mode) },
            apkUrl = session.apkUrl,
            apkSha256 = session.apkSha256,
            canFetchHash = session.apkUrl.isNotBlank() && !session.isFetchingHash,
            isFetchingHash = session.isFetchingHash,
            targetPackage = session.targetPackage,
            targetUrl = session.targetUrl,
            isRunningAll = session.isRunningAll,
        )
    }

    private fun row(id: CheckId, session: DeviceOwnerSession, mode: AgentMode?): DeviceOwnerRowUi {
        val result = session.results[id]
        val isRunning = id in session.running
        val notApplicable = id in DEVICE_OWNER_ONLY && mode == AgentMode.NORMAL
        val runnable = when {
            session.isRunningAll || isRunning -> false
            id in DEVICE_OWNER_ONLY -> mode == AgentMode.DEVICE_OWNER &&
                (id != CheckId.D3 || (session.apkUrl.isNotBlank() && session.apkSha256.isNotBlank()))
            else -> true
        }
        return DeviceOwnerRowUi(
            id = id.name,
            title = "${id.name} ${id.title}",
            badge = if (notApplicable) Badge.NA else result?.status?.toBadge() ?: Badge.PENDING,
            summary = when {
                notApplicable -> NOT_APPLICABLE_TEXT
                else -> result?.summaryText() ?: PENDING_SUMMARY
            },
            isRunning = isRunning,
            enabled = runnable,
            hasDelayedVariant = id == CheckId.D5,
        )
    }

    companion object {
        /** Строки экрана в порядке макета; D1 показывает режим, R13 — серийный номер (маска). */
        val ROWS = listOf(
            CheckId.D1, CheckId.D2, CheckId.D3, CheckId.D4, CheckId.D5, CheckId.D6, CheckId.R10, CheckId.R13,
        )

        /** Строки, недоступные без роли Device Owner. */
        val DEVICE_OWNER_ONLY = setOf(
            CheckId.D2, CheckId.D3, CheckId.D4, CheckId.D5, CheckId.D6, CheckId.R10,
        )

        const val NOT_APPLICABLE_TEXT = "только в режиме Device Owner"
        private const val TITLE_ACTIVE = "Device Owner активен"
        private const val TITLE_NORMAL = "Обычный режим"
        private const val TITLE_UNKNOWN = "Проверка режима…"
        private const val OWNER = "ru.restofleet.agent"
        private const val SERIAL_KEY = "serial"
        private const val NO_VALUE = "—"
        private const val PENDING_SUMMARY = "⏳ не запускалась"
    }
}
