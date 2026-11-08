package ru.restofleet.agent.presentation.deviceowner.viewmodel.model

import ru.restofleet.agent.domain.capability.AgentMode
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

/** Строки экрана в порядке макета; D1 показывает режим, R13 — серийный номер (маска). */
val DEVICE_OWNER_ROWS = listOf(
    CheckId.D1, CheckId.D2, CheckId.D3, CheckId.D4, CheckId.D5, CheckId.D6, CheckId.R10, CheckId.R13,
)

/** Строки, недоступные без роли Device Owner. */
val DEVICE_OWNER_ONLY = setOf(CheckId.D2, CheckId.D3, CheckId.D4, CheckId.D5, CheckId.D6, CheckId.R10)

/**
 * Единое правило «строку можно запустить» для ViewModel и маппера: строка входит в экран, ничего не выполняется,
 * для DO-строк роль Device Owner известна, а D3 имеет адрес и хэш. Пока режим неизвестен (`device == null`), DO-строки
 * недоступны.
 */
fun DeviceOwnerSession.canRun(id: CheckId): Boolean = when {
    id !in DEVICE_OWNER_ROWS || isRunningAll || id in running -> false
    id in DEVICE_OWNER_ONLY && device?.mode != AgentMode.DEVICE_OWNER -> false
    id == CheckId.D3 -> apkUrl.isNotBlank() && apkSha256.isNotBlank()
    else -> true
}

/** «Запустить все проверки DO» можно, только пока ничего не выполняется (ни прогон, ни одиночная строка). */
val DeviceOwnerSession.canRunAll: Boolean get() = !isRunningAll && running.isEmpty()
