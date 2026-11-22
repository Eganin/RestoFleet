package ru.restofleet.console.feature.provisioning.ui.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.restofleet.console.core.mvi.BaseMviViewModel
import ru.restofleet.console.core.mvi.MviStore
import ru.restofleet.console.core.mvi.defaultViewModelScope
import ru.restofleet.console.feature.provisioning.domain.IssueCode
import ru.restofleet.console.feature.provisioning.domain.LoadRestaurantChoices
import ru.restofleet.console.feature.provisioning.domain.ObserveCodeCountdown
import ru.restofleet.console.feature.provisioning.ui.api.IProvisioningRouter
import ru.restofleet.console.feature.provisioning.ui.viewmodel.mapper.ProvisioningUiStateMapper
import ru.restofleet.shared.kernel.Outcome

/**
 * Новое устройство: ресторан, роль, имя → одноразовый код с обратным отсчётом. Код не сохраняется и не логируется;
 * по истечении срока, по «Новый код» и при уходе с экрана он стирается из состояния.
 */
class ProvisioningViewModel(
    private val loadRestaurants: LoadRestaurantChoices,
    private val issueCode: IssueCode,
    private val countdown: ObserveCodeCountdown,
    private val router: IProvisioningRouter,
    scope: CoroutineScope = defaultViewModelScope(),
) : BaseMviViewModel<ProvisioningSession, ProvisioningUiState, ProvisioningIntent, ProvisioningAction, ProvisioningEffect>(
    store = MviStore(ProvisioningSession(), ProvisioningReducer(), ProvisioningUiStateMapper()::map),
    scope = scope,
) {
    private var countdownJob: Job? = null

    init {
        scope.launch {
            (loadRestaurants() as? Outcome.Success)?.let { reduce(ProvisioningAction.RestaurantsLoaded(it.value)) }
        }
    }

    override fun onIntent(intent: ProvisioningIntent) {
        when (intent) {
            is ProvisioningIntent.User.SelectRestaurant -> reduce(ProvisioningAction.RestaurantSelected(intent.restaurantId))
            is ProvisioningIntent.User.SelectRole -> reduce(ProvisioningAction.RoleSelected(intent.role))
            is ProvisioningIntent.User.NameChanged -> reduce(ProvisioningAction.NameChanged(intent.name))
            ProvisioningIntent.User.Issue -> issue()
            ProvisioningIntent.User.Copy -> state.issued?.let { emitEffect(ProvisioningEffect.CopyToClipboard(it.code)) }
            ProvisioningIntent.User.NewCode -> {
                countdownJob?.cancel()
                reduce(ProvisioningAction.Reset)
            }
            ProvisioningIntent.User.Back -> router.back()
        }
    }

    override fun onDestroy() {
        countdownJob?.cancel()
        reduce(ProvisioningAction.Reset)
        super.onDestroy()
    }

    private fun issue() {
        val s = state
        if (s.issuing || s.issued != null) return
        reduce(ProvisioningAction.IssueStarted)
        scope.launch {
            when (val result = issueCode(s.restaurantId, s.role, s.name)) {
                is Outcome.Failure -> reduce(ProvisioningAction.IssueFailed(result.error))
                is Outcome.Success -> {
                    reduce(ProvisioningAction.Issued(result.value, 1))
                    startCountdown(result.value.expiresAt)
                }
            }
        }
    }

    private fun startCountdown(expiresAt: Long) {
        countdownJob?.cancel()
        countdownJob = scope.launch { countdown(expiresAt).collect { reduce(ProvisioningAction.Tick(it)) } }
    }
}
