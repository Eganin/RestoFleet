package ru.restofleet.agent.data.mqtt

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.shared.kernel.Outcome
import javax.net.ssl.TrustManagerFactory
import kotlin.coroutines.cancellation.CancellationException

/**
 * Что нужно сессии до подключения: учётка и CA. CA берётся только из [CaSource] (fail closed): нет источника
 * `ca_missing`, битый PEM `ca_invalid`; системное хранилище доверия не подмешивается.
 */
internal class SessionPrerequisites(
    private val credentials: CredentialStore,
    private val caSource: CaSource,
    private val log: (String) -> Unit,
) {
    /** `null`: учётки нет или хранилище не читается (в лог идёт только класс сбоя). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // изоляция сбоя хранилища (канон 78)
    suspend fun settings(): BrokerSettings? =
        try {
            credentials.load()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        } catch (e: Exception) {
            log("credentials load failed: ${e::class.java.simpleName}")
            null
        }

    fun trust(): Outcome<TrustManagerFactory, MqttError> {
        val pem = caSource.pem() ?: return Outcome.Failure(MqttError.CaMissing)
        return when (val trust = CaTrust.trustManagerFactory(pem)) {
            is Outcome.Success -> trust
            is Outcome.Failure -> Outcome.Failure(MqttError.CaInvalid)
        }
    }
}
