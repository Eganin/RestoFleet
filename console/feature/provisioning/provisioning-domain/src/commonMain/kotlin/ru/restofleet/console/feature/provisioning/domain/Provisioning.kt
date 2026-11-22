package ru.restofleet.console.feature.provisioning.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

enum class DeviceRole(val wire: String) {
    Kitchen("kitchen"),
    Hall("hall"),
    Assembly("assembly"),
}

data class RestaurantChoice(val id: String, val label: String)

data class ProvisioningRequest(val restaurantId: String, val role: DeviceRole, val name: String?)

/** Выданный одноразовый код (FR-CN-09). Код — секрет: в `toString` не попадает, на диск и в журнал не пишется. */
data class IssuedCode(
    val id: String,
    val code: String,
    val restaurantId: String,
    val role: DeviceRole,
    val name: String?,
    val expiresAt: Long,
) {
    override fun toString(): String = "IssuedCode(id=$id, code=***, restaurantId=$restaurantId, role=$role)"
}

interface ProvisioningRepository {
    suspend fun restaurants(): Outcome<List<RestaurantChoice>, ConsoleError>

    suspend fun issue(request: ProvisioningRequest): Outcome<IssuedCode, ConsoleError>
}

class LoadRestaurantChoices(private val repository: ProvisioningRepository) {
    suspend operator fun invoke(): Outcome<List<RestaurantChoice>, ConsoleError> = repository.restaurants()
}

/** Выпуск кода: ресторан обязателен, имя — необязательное, не длиннее 100 символов. */
class IssueCode(private val repository: ProvisioningRepository) {
    suspend operator fun invoke(restaurantId: String?, role: DeviceRole?, name: String): Outcome<IssuedCode, ConsoleError> {
        val trimmed = name.trim()
        return when {
            restaurantId == null -> Outcome.Failure(ConsoleError.InvalidInput(FIELD_RESTAURANT, "empty"))
            role == null -> Outcome.Failure(ConsoleError.InvalidInput(FIELD_ROLE, "empty"))
            trimmed.length > NAME_MAX -> Outcome.Failure(ConsoleError.InvalidInput(FIELD_NAME, "too_long"))
            else -> repository.issue(ProvisioningRequest(restaurantId, role, trimmed.ifEmpty { null }))
        }
    }

    companion object {
        const val FIELD_RESTAURANT = "restaurant"
        const val FIELD_ROLE = "role"
        const val FIELD_NAME = "name"
        const val NAME_MAX = 100
    }
}

/** Оставшееся время жизни кода раз в секунду; завершается на нуле. */
class ObserveCodeCountdown(private val clock: Clock) {
    operator fun invoke(expiresAt: Long): Flow<Long> = flow {
        while (true) {
            val left = (expiresAt - clock.nowMillis()).coerceAtLeast(0)
            emit(left)
            if (left == 0L) break
            delay(TICK_MS)
        }
    }

    companion object {
        const val TICK_MS = 1_000L
    }
}
