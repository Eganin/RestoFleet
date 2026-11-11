package ru.restofleet.server.data.support

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import ru.restofleet.server.data.config.DataConfig
import ru.restofleet.server.data.config.IngestConfig
import ru.restofleet.server.data.config.MqttConfig
import ru.restofleet.server.data.di.dataModule
import ru.restofleet.server.data.di.domainModule
import ru.restofleet.server.data.mqtt.MqttBackend
import ru.restofleet.server.domain.auth.AccessToken
import ru.restofleet.server.domain.auth.AccessTokenIssuer
import ru.restofleet.server.domain.auth.AuthSettings
import ru.restofleet.server.domain.polling.PollingSettings
import ru.restofleet.server.domain.provisioning.BrokerEndpoint
import ru.restofleet.server.domain.provisioning.ProvisioningSettings

/**
 * Бэкенд без `server:api`: `dataModule` + `domainModule` из Koin с тем, что даст `server:app` (конфиг, настройки,
 * выпуск JWT). Так тесты проверяют и сценарии К3, и сборку графа зависимостей.
 */
class KoinBackend(mqtt: MqttConfig, ingest: IngestConfig = IngestConfig(batchWindowMs = 100)) : AutoCloseable {
    private val app = koinApplication {
        modules(
            module {
                single { DataConfig(TestDatabase.config.copy(maxPoolSize = 4), mqtt, ingest) }
                single { ProvisioningSettings(BrokerEndpoint("10.0.2.2", 8883)) }
                single { PollingSettings() }
                single { AuthSettings() }
                single { AccessTokenIssuer { AccessToken("test-jwt", 0) } }
            },
            dataModule,
            domainModule,
        )
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val koin: Koin get() = app.koin

    inline fun <reified T : Any> get(): T = koin.get()

    /** Подключение, подписки и запуск ingest; ждёт готовности. */
    fun start(): KoinBackend = apply {
        val backend = get<MqttBackend>()
        backend.start(scope)
        runBlocking { withTimeout(START_TIMEOUT_MS) { backend.awaitReady() } }
    }

    override fun close() {
        runBlocking { get<MqttBackend>().stop() }
        scope.cancel()
        app.close()
    }

    private companion object {
        const val START_TIMEOUT_MS = 15_000L
    }
}
