package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.restofleet.agent.domain.model.Registration
import ru.restofleet.agent.domain.model.RegistrationState
import ru.restofleet.agent.domain.port.CredentialStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Есть ли у агента учётка (навигация «нет учётки → мастер, есть → статус», план 3.6). Пароль наружу не выходит.
 * Хранилище бросило — [RegistrationState.Unavailable] (временный сбой Keystore, реш. 174): учётка, скорее всего, цела,
 * и предлагать новый код нельзя — он погасил бы старую регистрацию.
 */
class GetRegistration(private val store: CredentialStore) {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // изоляция сбоя хранилища (канон 78)
    suspend operator fun invoke(): RegistrationState =
        try {
            store.load()?.let { s ->
                RegistrationState.Registered(Registration(s.deviceId, s.role, s.restaurantId, s.host, s.port))
            } ?: RegistrationState.NotRegistered
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            RegistrationState.Unavailable
        } catch (e: Exception) {
            RegistrationState.Unavailable
        }
}
