package ru.restofleet.server.app.config

import ru.restofleet.server.api.ApiSettings
import ru.restofleet.server.api.JwtSettings
import ru.restofleet.server.api.RateLimitSettings
import ru.restofleet.server.data.config.DataConfig
import ru.restofleet.server.data.config.DatabaseConfig
import ru.restofleet.server.data.config.MqttConfig
import ru.restofleet.server.domain.auth.AuthSettings
import ru.restofleet.server.domain.polling.PollingSettings
import ru.restofleet.server.domain.provisioning.BrokerEndpoint
import ru.restofleet.server.domain.provisioning.ProvisioningSettings

/**
 * Готовые настройки модулей с прочитанными секретами. Каждая команда читает только свои секреты: `serve` — пароль БД,
 * пароль `backend` MQTT и секрет JWT; `migrate` и `seed` — только пароль БД (MQTT и JWT им не нужны: соединение с
 * брокером в этих командах не создаётся, вход не выполняется).
 */
class RuntimeConfig private constructor(
    val data: DataConfig,
    /** `null` — команда без HTTP (`migrate`, `seed`). */
    val api: ApiSettings?,
    val provisioning: ProvisioningSettings,
    val polling: PollingSettings,
    val auth: AuthSettings,
    val jobs: JobsSection,
) {
    companion object {
        private const val MS_PER_MINUTE = 60_000L
        private const val MS_PER_HOUR = 60 * MS_PER_MINUTE
        private const val MS_PER_DAY = 24 * MS_PER_HOUR

        fun forServe(config: AppConfig, read: (String) -> String = SecretFiles::read): RuntimeConfig = build(
            config = config,
            mqttPassword = read(config.mqtt.passwordFile),
            api = ApiSettings(
                jwt = JwtSettings(
                    secret = read(config.jwt.secretFile),
                    issuer = config.jwt.issuer,
                    audience = config.jwt.audience,
                    accessTtlMillis = config.jwt.accessTtlMinutes * MS_PER_MINUTE,
                ),
                rateLimit = RateLimitSettings(
                    loginPerPeriod = config.rateLimit.loginPerMinute,
                    provisionPerPeriod = config.rateLimit.provisionPerMinute,
                    pollAuthFailuresPerPeriod = config.rateLimit.pollAuthFailuresPerMinute,
                    periodMillis = MS_PER_MINUTE,
                ),
                maxBodyBytes = config.http.maxBodyBytes,
            ),
            read = read,
        )

        fun forDatabaseOnly(config: AppConfig, read: (String) -> String = SecretFiles::read): RuntimeConfig =
            build(config, mqttPassword = "", api = null, read = read)

        private fun build(
            config: AppConfig,
            mqttPassword: String,
            api: ApiSettings?,
            read: (String) -> String,
        ): RuntimeConfig = RuntimeConfig(
            data = DataConfig(
                database = DatabaseConfig(
                    jdbcUrl = config.database.jdbcUrl,
                    user = config.database.user,
                    password = read(config.database.passwordFile),
                    maxPoolSize = config.database.maxPoolSize,
                ),
                mqtt = MqttConfig(
                    host = config.mqtt.host,
                    port = config.mqtt.port,
                    username = config.mqtt.username,
                    password = mqttPassword,
                    clientId = config.mqtt.clientId,
                    caFile = config.mqtt.caFile,
                ),
            ),
            api = api,
            provisioning = ProvisioningSettings(
                broker = BrokerEndpoint(config.provisioning.brokerHost, config.provisioning.brokerPort),
                tokenTtlMillis = config.provisioning.tokenTtlHours * MS_PER_HOUR,
            ),
            polling = PollingSettings(staleAfterMillis = config.polling.staleAfterMs),
            auth = AuthSettings(refreshTtlMillis = config.auth.refreshTtlDays * MS_PER_DAY),
            jobs = config.jobs,
        )
    }
}
