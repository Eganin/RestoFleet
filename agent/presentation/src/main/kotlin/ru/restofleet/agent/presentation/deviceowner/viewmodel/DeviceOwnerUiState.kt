package ru.restofleet.agent.presentation.deviceowner.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.agent.presentation.common.Badge

/**
 * Строка проверки. [id] — имя `CheckId`; [enabled] — строку можно запустить; [hasDelayedVariant] — есть кнопка
 * «через 30 с» (D5).
 */
data class DeviceOwnerRowUi(
    val id: String,
    val title: String,
    val badge: Badge,
    val summary: String,
    val isRunning: Boolean,
    val enabled: Boolean,
    val hasDelayedVariant: Boolean = false,
)

@Immutable
data class DeviceOwnerUiState(
    val title: String,
    val isDeviceOwner: Boolean,
    val owner: String,
    val serial: String,
    val rows: List<DeviceOwnerRowUi>,
    val apkUrl: String,
    val apkSha256: String,
    val canFetchHash: Boolean,
    val isFetchingHash: Boolean,
    val targetPackage: String,
    val targetUrl: String,
    val isRunningAll: Boolean,
    val canRunAll: Boolean,
)
