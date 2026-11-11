package ru.restofleet.server.app.config

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.addFileSource
import com.sksamuel.hoplite.addResourceSource
import com.sksamuel.hoplite.sources.EnvironmentVariablesPropertySource

/**
 * Конфигурация бэкенда (hoplite, план 2.4): переменные окружения `RF_…` (`RF_DATABASE_JDBCURL`,
 * `RF_MQTT_HOST`, `RF_RATELIMIT_PROVISIONPERMINUTE`…) → необязательный файл `RF_CONFIG` → `application.yaml` из
 * ресурсов. **Секретов здесь нет** — только пути к файлам с ними (Docker secrets / `deploy/out/…`, реш. 252): значения
 * не попадают ни в окружение, ни в argv, ни в отчёт hoplite.
 */
data class AppConfig(
    val http: HttpSection = HttpSection(),
    val database: DatabaseSection,
    val mqtt: MqttSection,
    val jwt: JwtSection,
    val provisioning: ProvisioningSection = ProvisioningSection(),
    val polling: PollingSection = PollingSection(),
    val auth: AuthSection = AuthSection(),
    val rateLimit: RateLimitSection = RateLimitSection(),
    val jobs: JobsSection = JobsSection(),
    /** Каталог секретов для `seed` (`passwordSecret` в `users.yaml`): в контейнере — `/run/secrets`. */
    val secretsDir: String = "/run/secrets",
)

data class HttpSection(val host: String = "0.0.0.0", val port: Int = 8080, val maxBodyBytes: Long = 1_048_576)

data class DatabaseSection(
    val jdbcUrl: String,
    val user: String,
    val passwordFile: String,
    val maxPoolSize: Int = 10,
)

/** Брокер — только TLS с CA стенда ([caFile] обязателен; TCP без TLS — только тесты `server:data`, реш. 232). */
data class MqttSection(
    val host: String,
    val port: Int = 8883,
    val username: String = "backend",
    val clientId: String = "backend",
    val passwordFile: String,
    val caFile: String,
)

data class JwtSection(
    val secretFile: String,
    val issuer: String = "restofleet",
    val audience: String = "restofleet-api",
    val accessTtlMinutes: Long = 15,
)

/** Адрес брокера, который агент получает в ответе `/provision` (эмулятор стенда: `10.0.2.2:8883`). */
data class ProvisioningSection(
    val brokerHost: String = "10.0.2.2",
    val brokerPort: Int = 8883,
    val tokenTtlHours: Long = 24,
)

data class PollingSection(val staleAfterMs: Long = 90_000)

data class AuthSection(val refreshTtlDays: Long = 7)

data class RateLimitSection(val loginPerMinute: Int = 10, val provisionPerMinute: Int = 60)

/** Фоновые задачи (реш. 214, контракт КМ2b): истечение команд и presence опроса — каждые 5 с. */
data class JobsSection(
    val expireCommandsMs: Long = 5_000,
    val markStalePollersMs: Long = 5_000,
    val metricsLogMs: Long = 60_000,
)

object AppConfigLoader {
    const val ENV_PREFIX = "RF_"
    const val CONFIG_FILE_ENV = "RF_CONFIG"
    private const val RESOURCE = "/application.yaml"

    @OptIn(ExperimentalHoplite::class)
    fun load(env: Map<String, String>): AppConfig = ConfigLoaderBuilder.defaultWithoutPropertySources()
        // Sealed-типов в конфиге нет; явный режим убирает предупреждение hoplite в stdout
        .withExplicitSealedTypes()
        .addPropertySource(EnvironmentVariablesPropertySource({ env - CONFIG_FILE_ENV }, ENV_PREFIX))
        .apply { env[CONFIG_FILE_ENV]?.let { addFileSource(it, optional = false) } }
        .addResourceSource(RESOURCE)
        .build()
        .loadConfigOrThrow<AppConfig>()
}
