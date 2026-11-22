package ru.restofleet.server.data.di

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.koin.core.annotation.KoinInternalApi
import ru.restofleet.server.data.config.MqttConfig
import ru.restofleet.server.data.mqtt.HiveMqClient
import ru.restofleet.server.data.mqtt.MqttBackend
import ru.restofleet.server.data.support.KoinBackend
import ru.restofleet.server.data.support.TestDatabase
import ru.restofleet.server.domain.common.TransactionRunner
import ru.restofleet.server.domain.health.usecase.CheckHealth
import kotlin.reflect.KClass

/** Граф `dataModule` + `domainModule` собирается целиком: каждый use case и адаптер создаётся без MQTT-подключения. */
class ModulesTest {

    @OptIn(KoinInternalApi::class)
    @Test
    fun `every domain use case and data adapter resolves from the modules`() {
        TestDatabase.reset()
        KoinBackend(MqttConfig(host = "127.0.0.1", port = 1, password = "unused")).use { backend ->
            val useCases: List<KClass<*>> = domainModule.mappings.values.map { it.beanDefinition.primaryType }

            useCases.forEach { type -> assertThat(backend.koin.get<Any>(type)).isNotNull() }
            assertThat(useCases.size).isAtLeast(30)
            assertThat(backend.get<MqttBackend>()).isNotNull()
            assertThat(backend.get<TransactionRunner>()).isNotNull()
            assertThat(backend.get<HiveMqClient>().isConnected).isFalse()

            val health = runBlocking { backend.get<CheckHealth>()() }
            assertThat(health.components).containsExactly("database", true, "broker", false)
        }
    }
}
