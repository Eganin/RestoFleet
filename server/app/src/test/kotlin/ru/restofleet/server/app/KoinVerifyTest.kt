package ru.restofleet.server.app

import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.verify.definition
import org.koin.test.verify.injectedParameters
import org.koin.test.verify.verify
import ru.restofleet.server.api.JwtSettings
import ru.restofleet.server.app.config.AppConfigLoader
import ru.restofleet.server.app.config.RuntimeConfig
import ru.restofleet.server.app.di.databaseModules
import ru.restofleet.server.app.di.serveModules
import ru.restofleet.server.data.config.DatabaseConfig
import ru.restofleet.server.data.config.IngestConfig
import ru.restofleet.server.data.config.MqttConfig
import ru.restofleet.server.domain.auth.AccessTokenIssuer
import ru.restofleet.server.domain.health.usecase.CheckHealth
import ru.restofleet.server.domain.provisioning.BrokerEndpoint
import javax.net.ssl.TrustManagerFactory
import kotlin.reflect.KClass

/**
 * Статическая проверка графа Koin (`koin-test` `verify`, арх. разд. 6): у каждого определения есть все зависимости
 * конструктора. Без БД и брокера; разрешение всех бинов на живой БД — `BackendOnPostgresTest`.
 */
@OptIn(KoinExperimentalAPI::class)
class KoinVerifyTest {
    private val config = AppConfigLoader.load(emptyMap())
    private val secret = { _: String -> "x".repeat(40) }

    @Test
    fun `serve modules verify`() {
        module { includes(serveModules(RuntimeConfig.forServe(config, secret))) }
            .verify(extraTypes = EXTRA_TYPES, injections = INJECTIONS)
    }

    @Test
    fun `migrate and seed modules verify`() {
        // Без apiModule нет выпуска JWT: Login/RefreshSession в migrate и seed не создаются (вход не выполняется)
        module { includes(databaseModules(RuntimeConfig.forDatabaseOnly(config, secret))) }
            .verify(extraTypes = EXTRA_TYPES + AccessTokenIssuer::class, injections = INJECTIONS)
    }

    private companion object {
        /**
         * Типы в конструкторах бинов, которые создаются лямбдой или из значений конфигурации: `verify` смотрит на
         * конструктор класса, а не на лямбду. Hikari — implementation-зависимость `server:data`, отсюда по имени.
         */
        val EXTRA_TYPES: List<KClass<*>> = listOf(
            DatabaseConfig::class,
            MqttConfig::class,
            IngestConfig::class,
            BrokerEndpoint::class,
            JwtSettings::class,
            TrustManagerFactory::class,
            Class.forName("com.zaxxer.hikari.HikariConfig").kotlin,
        )

        /**
         * `CheckHealth(probes: List<HealthProbe>)` собирается лямбдой из двух проб `dataModule`; список `verify` не
         * сопоставляет ни с одним типом, поэтому параметр объявлен «внешним». Что граф действительно создаёт
         * `CheckHealth`, проверяет `BackendOnPostgresTest` (и `ModulesTest` в `server:data`).
         */
        val INJECTIONS = injectedParameters(definition<CheckHealth>(List::class))
    }
}
