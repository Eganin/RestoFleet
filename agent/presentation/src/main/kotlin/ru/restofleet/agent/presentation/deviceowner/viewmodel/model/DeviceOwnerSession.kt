package ru.restofleet.agent.presentation.deviceowner.viewmodel.model

import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.console.core.mvi.contract.MviState

/**
 * Источник истины экрана Device Owner. [running] — проверки в работе; [isRunningAll] — идёт «Запустить все проверки
 * DO»; [targetPackage]/[targetUrl] — цель D4 и D5.
 */
data class DeviceOwnerSession(
    val device: DeviceInfo? = null,
    val results: Map<CheckId, CheckResult> = emptyMap(),
    val running: Set<CheckId> = emptySet(),
    val isRunningAll: Boolean = false,
    val apkUrl: String = DEFAULT_APK_URL,
    val apkSha256: String = "",
    val isFetchingHash: Boolean = false,
    val targetPackage: String = DEFAULT_TARGET_PACKAGE,
    val targetUrl: String = "",
) : MviState

/** Адрес тестового APK стенда с точки зрения эмулятора (`deploy/README.md`). */
const val DEFAULT_APK_URL = "https://10.0.2.2:8443/waiter-4.13.apk"

/** Цель D4/D5 по умолчанию — тестовый `tools:dummy-waiter`. */
const val DEFAULT_TARGET_PACKAGE = "ru.restofleet.dummy.waiter"
