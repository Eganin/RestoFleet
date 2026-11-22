package ru.restofleet.console.feature.provisioning.ui.viewmodel

import androidx.compose.runtime.Immutable
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.core.mvi.contract.MviAction
import ru.restofleet.console.core.mvi.contract.MviEffect
import ru.restofleet.console.core.mvi.contract.MviIntent
import ru.restofleet.console.core.mvi.contract.MviState
import ru.restofleet.console.feature.provisioning.domain.DeviceRole
import ru.restofleet.console.feature.provisioning.domain.IssuedCode
import ru.restofleet.console.feature.provisioning.domain.RestaurantChoice

/** Состояние «Нового устройства». Выданный код живёт только здесь и стирается по истечении срока или с экраном. */
data class ProvisioningSession(
    val restaurants: List<RestaurantChoice> = emptyList(),
    val restaurantId: String? = null,
    val role: DeviceRole? = DeviceRole.Kitchen,
    val name: String = "",
    val issuing: Boolean = false,
    val issued: IssuedCode? = null,
    val remainingMs: Long = 0L,
    val expired: Boolean = false,
    val error: ConsoleError? = null,
) : MviState

sealed interface ProvisioningIntent : MviIntent {
    sealed interface User : ProvisioningIntent {
        data class SelectRestaurant(val restaurantId: String) : User
        data class SelectRole(val role: DeviceRole) : User
        data class NameChanged(val name: String) : User
        data object Issue : User
        data object Copy : User
        data object NewCode : User
        data object Back : User
    }
}

sealed interface ProvisioningAction : MviAction {
    data class RestaurantsLoaded(val restaurants: List<RestaurantChoice>) : ProvisioningAction
    data class RestaurantSelected(val id: String) : ProvisioningAction
    data class RoleSelected(val role: DeviceRole) : ProvisioningAction
    data class NameChanged(val name: String) : ProvisioningAction
    data object IssueStarted : ProvisioningAction
    data class Issued(val code: IssuedCode, val remainingMs: Long) : ProvisioningAction
    data class IssueFailed(val error: ConsoleError) : ProvisioningAction
    data class Tick(val remainingMs: Long) : ProvisioningAction
    data object Reset : ProvisioningAction
}

sealed interface ProvisioningEffect : MviEffect {
    /** Код копируется в буфер обмена платформой; ViewModel код никуда не пишет. */
    data class CopyToClipboard(val text: String) : ProvisioningEffect

    data class ShowMessage(val text: String) : ProvisioningEffect
}

@Immutable
data class ChoiceUi(val value: String, val label: String, val selected: Boolean)

@Immutable
data class ProvisioningUiState(
    val restaurants: List<ChoiceUi>,
    val roles: List<ChoiceUi>,
    val name: String,
    val issuing: Boolean,
    val canIssue: Boolean,
    val code: String?,
    val countdown: String?,
    val expiredText: String?,
    val adbHint: String?,
    val errorText: String?,
    val nameError: String?,
)
