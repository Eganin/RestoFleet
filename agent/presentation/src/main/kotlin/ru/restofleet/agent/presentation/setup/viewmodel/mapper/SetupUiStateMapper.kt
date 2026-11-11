package ru.restofleet.agent.presentation.setup.viewmodel.mapper

import ru.restofleet.agent.domain.error.ProvisionError
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.StringSpec
import ru.restofleet.agent.presentation.common.permissionRows
import ru.restofleet.agent.presentation.common.toRows
import ru.restofleet.agent.presentation.setup.viewmodel.SetupUiState
import ru.restofleet.agent.presentation.setup.viewmodel.model.SetupSession
import ru.restofleet.agent.presentation.setup.viewmodel.model.SetupStep
import ru.restofleet.agent.presentation.setup.viewmodel.model.canSubmit

/** Сессия мастера → UI: заголовок шага, строки разрешений, текст ошибки регистрации, итог без пароля. */
class SetupUiStateMapper {
    fun map(session: SetupSession): SetupUiState = SetupUiState(
        step = session.step,
        stepTitle = StringSpec(
            when (session.step) {
                SetupStep.PERMISSIONS -> R.string.setup_step_permissions
                SetupStep.REGISTRATION -> R.string.setup_step_registration
                SetupStep.DONE -> R.string.setup_step_done
            },
        ),
        permissions = permissionRows(session.granted),
        baseUrl = session.baseUrl,
        code = session.code,
        canSubmit = session.canSubmit,
        isSubmitting = session.isSubmitting,
        error = session.error?.toStringSpec(),
        result = session.registration?.toRows().orEmpty(),
        labAvailable = session.labAvailable,
    )
}

/** Отказ регистрации → текст для пользователя; введённые значения в текст не попадают. */
@Suppress("CyclomaticComplexMethod") // таблица «класс ошибки → строка»: исчерпывающий when проверяет компилятор
internal fun ProvisionError.toStringSpec(): StringSpec = when (this) {
    ProvisionError.InvalidBaseUrl -> StringSpec(R.string.setup_error_invalid_base_url)
    ProvisionError.EmptyCode -> StringSpec(R.string.setup_error_empty_code)
    ProvisionError.CodeInvalid -> StringSpec(R.string.setup_error_code_invalid)
    ProvisionError.CodeUsed -> StringSpec(R.string.setup_error_code_used)
    ProvisionError.CodeExpired -> StringSpec(R.string.setup_error_code_expired)
    is ProvisionError.RateLimited -> retryAfterSec?.let {
        StringSpec(R.string.setup_error_rate_limited_after, listOf(it.toString()))
    } ?: StringSpec(R.string.setup_error_rate_limited)
    ProvisionError.ServerUnavailable -> StringSpec(R.string.setup_error_server_unavailable)
    is ProvisionError.Rejected -> StringSpec(R.string.setup_error_rejected, listOf(reason))
    ProvisionError.NoNetwork -> StringSpec(R.string.setup_error_no_network)
    ProvisionError.Timeout -> StringSpec(R.string.setup_error_timeout)
    ProvisionError.Tls -> StringSpec(R.string.setup_error_tls)
    ProvisionError.CaMissing -> StringSpec(R.string.setup_error_ca_missing)
    ProvisionError.InvalidResponse -> StringSpec(R.string.setup_error_invalid_response)
    ProvisionError.StorageFailed -> StringSpec(R.string.setup_error_storage)
    is ProvisionError.Failed -> StringSpec(R.string.setup_error_failed, listOf(cause))
}
