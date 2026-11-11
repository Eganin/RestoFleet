package ru.restofleet.agent.presentation.setup.viewmodel.model

import ru.restofleet.agent.domain.capability.Permission
import ru.restofleet.agent.domain.error.ProvisionError
import ru.restofleet.agent.domain.model.Registration
import ru.restofleet.agent.presentation.common.SettingsTarget
import ru.restofleet.console.core.mvi.contract.MviState

/** Шаги мастера (FR-AG-04 базово, FR-AG-10): разрешения → адрес стенда и код → результат. */
enum class SetupStep { PERMISSIONS, REGISTRATION, DONE }

/**
 * Источник истины мастера. [granted] — `null`, пока разрешения не прочитаны; [error] — отказ последней регистрации;
 * [registration] — итог (без пароля). Код после успеха забывается.
 */
data class SetupSession(
    val step: SetupStep = SetupStep.PERMISSIONS,
    val granted: Set<Permission>? = null,
    val baseUrl: String = DEFAULT_BASE_URL,
    val code: String = "",
    val isSubmitting: Boolean = false,
    val error: ProvisionError? = null,
    val registration: Registration? = null,
    val labAvailable: Boolean = false,
) : MviState

/** Стенд с точки зрения эмулятора: nginx `apk-host` проксирует `/api/` на бэкенд (реш. 211, контракт K2). */
const val DEFAULT_BASE_URL = "https://10.0.2.2:8443"

/** Разрешения шага мастера и экраны настроек для них (FR-AG-04): уведомления, usage access, батарея. */
val WIZARD_PERMISSIONS: List<Pair<Permission, SettingsTarget>> = listOf(
    Permission.POST_NOTIFICATIONS to SettingsTarget.NOTIFICATIONS,
    Permission.USAGE_ACCESS to SettingsTarget.USAGE_ACCESS,
    Permission.IGNORE_BATTERY_OPT to SettingsTarget.BATTERY_OPTIMIZATION,
)

/** Регистрацию можно отправить: есть адрес и код, предыдущая попытка завершилась. */
val SetupSession.canSubmit: Boolean
    get() = step == SetupStep.REGISTRATION && !isSubmitting && baseUrl.isNotBlank() && code.isNotBlank()
