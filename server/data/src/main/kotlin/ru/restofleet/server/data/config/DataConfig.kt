package ru.restofleet.server.data.config

/** Конфигурация data-слоя бэкенда; заполняет `server:app` (hoplite) и кладёт в Koin одним бином. */
data class DataConfig(
    val database: DatabaseConfig,
    val mqtt: MqttConfig,
    val ingest: IngestConfig = IngestConfig(),
    /** TTL кэша Basic-проверки устройства (реш. 213). */
    val deviceAuthCacheTtlMs: Long = DEFAULT_AUTH_CACHE_TTL_MS,
) {
    companion object {
        const val DEFAULT_AUTH_CACHE_TTL_MS = 5L * 60 * 1000
    }
}

/** PostgreSQL 17 (TimescaleDB на стенде) через HikariCP. [toString] пароль не раскрывает. */
data class DatabaseConfig(
    val jdbcUrl: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int = 10,
    val connectionTimeoutMs: Long = 5_000,
) {
    override fun toString(): String =
        "DatabaseConfig(jdbcUrl=$jdbcUrl, user=$user, password=***, maxPoolSize=$maxPoolSize, " +
            "connectionTimeoutMs=$connectionTimeoutMs)"
}

/**
 * Подключение бэкенда к брокеру (реш. 230): учётка DynSec `backend`, `clientId` фиксирован (стабильный для постоянной
 * сессии, P10). [caFile] — PEM CA стенда; `null` — TCP без TLS, **только для тестов** (реш. 232). [toString] пароль не
 * раскрывает.
 */
data class MqttConfig(
    val host: String,
    val port: Int,
    val username: String = "backend",
    val password: String,
    val clientId: String = "backend",
    val caFile: String? = null,
    val keepAliveSec: Int = 30,
    val sessionExpirySec: Long = DEFAULT_SESSION_EXPIRY_SEC,
    /** Сколько неподтверждённых QoS 1 брокер может держать в полёте к бэкенду (MQTT 5 Receive Maximum). */
    val receiveMaximum: Int = 1_000,
    val requestTimeoutMs: Long = 5_000,
    val publishTimeoutMs: Long = 10_000,
) {
    override fun toString(): String =
        "MqttConfig(host=$host, port=$port, username=$username, password=***, clientId=$clientId, caFile=$caFile, " +
            "keepAliveSec=$keepAliveSec, sessionExpirySec=$sessionExpirySec, receiveMaximum=$receiveMaximum)"

    companion object {
        /** Session Expiry бэкенда — 24 ч, как у устройств (FR-BR-07): брокер копит сообщения, пока бэкенда нет. */
        const val DEFAULT_SESSION_EXPIRY_SEC: Long = 24L * 60 * 60
    }
}

/** Пакетирование ingest (P10, арх. разд. 8): до [batchSize] сообщений или [batchWindowMs] от первого. */
data class IngestConfig(
    val batchSize: Int = 500,
    val batchWindowMs: Long = 500,
    val retryInitialMs: Long = 1_000,
    val retryMaxMs: Long = 30_000,
    /** Сообщения больше этого размера не разбираются (ack + счётчик). */
    val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
) {
    companion object {
        const val DEFAULT_MAX_PAYLOAD_BYTES: Int = 256 * 1024
    }
}
