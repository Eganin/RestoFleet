package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.error.ProvisionError
import ru.restofleet.agent.domain.model.ProvisionedDevice
import ru.restofleet.agent.domain.model.Registration
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.agent.domain.port.DeviceDataReset
import ru.restofleet.agent.domain.port.ProvisioningApi
import ru.restofleet.shared.kernel.Outcome
import kotlin.coroutines.cancellation.CancellationException

/**
 * Регистрация по одноразовому коду (FR-AG-10, 12): адрес стенда и код проверяются до сети (`https://хост[:порт]`,
 * завершающий `/` отбрасывается; код — непустой после обрезки пробелов, остальное нормализует бэкенд), затем
 * [api] гасит код, и учётка брокера вместе с ролью и рестораном (реш. 210) уходит в [store] (Keystore; смена учётки
 * перезапускает MQTT-сессию). Если прежняя учётка — другого устройства (или её не прочитать), до записи новой [reset]
 * стирает тень, outbox и журнал команд прежнего (R-3, реш. 304); данные, накопленные до первой регистрации, остаются —
 * это то же физическое устройство. Отказы — [ProvisionError] без введённых значений; исключение порта — `failed` с
 * классом (канон 78), сбой сброса или записи — `storage_failed` (код уже погашен). Настоящая отмена и `Error`
 * пробрасываются.
 */
class Provision(
    private val api: ProvisioningApi,
    private val store: CredentialStore,
    private val reset: DeviceDataReset,
) {
    suspend operator fun invoke(baseUrl: String, code: String): Outcome<Registration, ProvisionError> {
        val url = normalizeBaseUrl(baseUrl)
        val trimmed = code.trim()
        return when {
            url == null -> Outcome.Failure(ProvisionError.InvalidBaseUrl)
            trimmed.isEmpty() -> Outcome.Failure(ProvisionError.EmptyCode)
            else -> when (val answer = isolated { api.provision(url, trimmed) }) {
                is Outcome.Failure -> answer
                is Outcome.Success -> persist(answer.value)
            }
        }
    }

    private suspend fun persist(device: ProvisionedDevice): Outcome<Registration, ProvisionError> {
        val settings = BrokerSettings(
            host = device.brokerHost,
            port = device.brokerPort,
            deviceId = device.deviceId,
            password = device.password,
            role = device.role,
            restaurantId = device.restaurantId,
        )
        val stored = isolated(onFailure = { ProvisionError.StorageFailed }) {
            if (belongsToAnotherDevice(device)) reset.clear()
            store.save(settings)
            Outcome.Success(Unit)
        }
        return when (stored) {
            is Outcome.Failure -> stored
            is Outcome.Success -> Outcome.Success(
                Registration(device.deviceId, device.role, device.restaurantId, device.brokerHost, device.brokerPort),
            )
        }
    }

    /** Сохранённая учётка — другого устройства; нечитаемая считается чужой (безопаснее стереть, чем смешать). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun belongsToAnotherDevice(device: ProvisionedDevice): Boolean = try {
        store.load()?.let { it.deviceId != device.deviceId } ?: false
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        true
    } catch (e: Exception) {
        true
    }

    // Текст исключения не сохраняется: в нём могут оказаться код, адрес или пароль
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T> isolated(
        onFailure: (Exception) -> ProvisionError = { ProvisionError.Failed(it::class.simpleName ?: "Unknown") },
        block: suspend () -> Outcome<T, ProvisionError>,
    ): Outcome<T, ProvisionError> =
        try {
            block()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            Outcome.Failure(onFailure(e))
        } catch (e: Exception) {
            Outcome.Failure(onFailure(e))
        }

    private companion object {
        const val HTTPS = "https://"

        /** `https://хост[:порт]` без пути, запроса и фрагмента; `null` — адрес не годится. */
        fun normalizeBaseUrl(raw: String): String? {
            val url = raw.trim().removeSuffix("/")
            val authority = url.removePrefix(HTTPS)
            val plainAuthority = url.startsWith(HTTPS) && authority.isNotEmpty() && authority.none { it in "/?#@" }
            return url.takeIf { plainAuthority && Capabilities.isHttpsUrl(it) }
        }
    }
}
