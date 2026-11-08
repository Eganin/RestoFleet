package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.restofleet.agent.domain.error.BrokerConfigError
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import kotlin.coroutines.cancellation.CancellationException

/**
 * Проверяет введённую учётку брокера и сохраняет её. Невалидное не доходит до хранилища: `deviceId` разбирает
 * [DeviceId] (`/`, `+`, `#`, пробел и пустая строка отклоняются), `host` — имя или IPv4 без схемы, порта и пробелов,
 * `port` в 1..65535, `password` непустой. Коды отказов не содержат введённых значений. Сбой хранилища — `StorageFailed`
 * (канон 78: ловится `Exception`, `Error` и настоящая отмена пробрасываются).
 */
class ConfigureBrokerCredentials(private val store: CredentialStore) {
    suspend operator fun invoke(
        host: String?,
        port: Int?,
        deviceId: String?,
        password: String?,
    ): Outcome<Unit, BrokerConfigError> =
        when (val parsed = validate(host, port, deviceId, password)) {
            is Outcome.Failure -> parsed
            is Outcome.Success -> persist(parsed.value)
        }

    private fun validate(
        host: String?,
        port: Int?,
        deviceId: String?,
        password: String?,
    ): Outcome<BrokerSettings, BrokerConfigError> {
        val id = deviceId?.let(DeviceId::parse)
        val error = when {
            host == null || !HOST.matches(host) -> BrokerConfigError.InvalidHost
            port == null || port !in PORTS -> BrokerConfigError.InvalidPort
            id == null -> BrokerConfigError.InvalidDeviceId
            password.isNullOrEmpty() -> BrokerConfigError.EmptyPassword
            else -> return Outcome.Success(BrokerSettings(host, port, id, password))
        }
        return Outcome.Failure(error)
    }

    // Причина сбоя намеренно не сохраняется: текст исключения хранилища может содержать введённые значения.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun persist(settings: BrokerSettings): Outcome<Unit, BrokerConfigError> =
        try {
            store.save(settings)
            Outcome.Success(Unit)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            Outcome.Failure(BrokerConfigError.StorageFailed)
        } catch (e: Exception) {
            Outcome.Failure(BrokerConfigError.StorageFailed)
        }

    private companion object {
        val HOST = Regex("^[A-Za-z0-9._-]{1,253}$")
        val PORTS = 1..65_535
    }
}
