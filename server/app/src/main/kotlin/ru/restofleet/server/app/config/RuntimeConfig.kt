package ru.restofleet.server.app.config

import ru.restofleet.server.api.ApiSettings
import ru.restofleet.server.api.JwtSettings
import ru.restofleet.server.api.RateLimitSettings
import ru.restofleet.server.data.config.DataConfig
import ru.restofleet.server.data.config.DatabaseConfig
import ru.restofleet.server.data.config.MqttConfig
import ru.restofleet.server.domain.alert.RuleParams
import ru.restofleet.server.domain.auth.AuthSettings
import ru.restofleet.server.domain.device.DeviceRole
import ru.restofleet.server.domain.incident.ClassifierParams
import ru.restofleet.server.domain.incident.IncidentSettings
import ru.restofleet.server.domain.polling.PollingSettings
import ru.restofleet.server.domain.provisioning.BrokerEndpoint
import ru.restofleet.server.domain.provisioning.ProvisioningSettings
import ru.restofleet.server.domain.shadow.DesiredDefaults
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue

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
    val rules: RuleParams,
    val incidents: IncidentSettings,
) {
    companion object {
        private const val MS_PER_SECOND = 1_000L
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
                desired = DesiredDefaults(
                    config.desired.entries.associate { (code, section) ->
                        val role = requireNotNull(DeviceRole.fromCode(code)) { "desired: unknown role '$code'" }
                        role to section.toDocument()
                    },
                ),
            ),
            polling = PollingSettings(staleAfterMillis = config.polling.staleAfterMs),
            auth = AuthSettings(
                refreshIdleMillis = config.auth.refreshIdleHours * MS_PER_HOUR,
                sessionMaxMillis = config.auth.sessionMaxDays * MS_PER_DAY,
            ),
            jobs = config.jobs,
            rules = RuleParams(freeMbEnter = config.alerts.freeMbEnter, freeMbExit = config.alerts.freeMbExit),
            incidents = config.incidents.let {
                IncidentSettings(
                    classifier = ClassifierParams(
                        backendDownPct = it.backendDownPct,
                        minRestaurants = it.minRestaurants,
                        restaurantSharePct = it.restaurantSharePct,
                        offlinePct = it.offlinePct,
                        primaryTarget = it.primaryTarget,
                        refTarget = it.refTarget,
                    ),
                    windowMillis = it.windowSec * MS_PER_SECOND,
                    silentAfterMillis = it.silentAfterHours * MS_PER_HOUR,
                    resolveAfterTicks = it.resolveAfterTicks,
                    tickMillis = it.tickMs,
                )
            },
        )
    }
}

/** Секция конфига → документ `desired` (те же ключи, что `ShadowDesiredDto`; проверку делает [DesiredDefaults]). */
internal fun RoleDesiredSection.toDocument(): ShadowDocument = ShadowDocument(
    mapOf(
        "telemetry" to obj("intervalSec" to num(intervalSec)),
        "thresholds" to obj(
            "batteryLowPct" to num(thresholds.batteryLowPct),
            "tempHighC" to ShadowValue.Num(thresholds.tempHighC),
            "fgAbsentMin" to num(thresholds.fgAbsentMin),
        ),
        "watchedApps" to ShadowValue.List(watchedApps.map { ShadowValue.Str(it) }),
        "probes" to ShadowValue.List(
            probes.map { p ->
                ShadowValue.Obj(
                    buildMap {
                        put("id", ShadowValue.Str(p.id))
                        put("type", ShadowValue.Str(p.type))
                        put("url", ShadowValue.Str(p.url))
                        put("intervalSec", num(p.intervalSec))
                        put("timeoutMs", ShadowValue.Num(p.timeoutMs.toDouble()))
                        put("failThreshold", num(p.failThreshold))
                        if (p.reference) put("reference", ShadowValue.Bool(true))
                        p.expectedStatus?.let { put("expectedStatus", num(it)) }
                    },
                )
            },
        ),
    ),
)

private fun obj(vararg pairs: Pair<String, ShadowValue>) = ShadowValue.Obj(mapOf(*pairs))

private fun num(value: Int) = ShadowValue.Num(value.toDouble())
