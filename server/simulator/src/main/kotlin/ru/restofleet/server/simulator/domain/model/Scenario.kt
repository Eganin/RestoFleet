package ru.restofleet.server.simulator.domain.model

/** Транспорт устройств: MQTT (как агент) или REST-polling (FR-SM-06, эксперимент ТЗ 13.2). */
enum class TransportMode(val code: String) {
    MQTT("mqtt"),
    REST("rest"),
}

/**
 * Как «пропадает» устройство в замере времени обнаружения (план 4.2): [SILENT] — связь пропала без DISCONNECT и без
 * закрытия TCP (брокер узнаёт по keep-alive, REST — по отсутствию опросов); [CLOSE] — соединение закрыто без
 * DISCONNECT (процесс убит: брокер сразу публикует LWT).
 */
enum class LossMode(val code: String) {
    SILENT("silent"),
    CLOSE("close"),
}

/** Команды измерителя: [perMinute] команд в минуту случайным устройствам через REST API бэкенда (admin). */
data class CommandLoad(val perMinute: Int, val type: String = "ping", val ttlSec: Int = DEFAULT_TTL_SEC) {
    init {
        require(perMinute in 1..MAX_PER_MINUTE) { "commands.perMinute must be in 1..$MAX_PER_MINUTE" }
        require(ttlSec in 1..MAX_TTL_SEC) { "commands.ttlSec must be in 1..$MAX_TTL_SEC" }
    }

    private companion object {
        const val DEFAULT_TTL_SEC = 300
        const val MAX_PER_MINUTE = 6_000
        const val MAX_TTL_SEC = 86_400
    }
}

/** Замер времени обнаружения пропажи: [devices] устройств пропадают, их статус опрашивается до `offline`. */
data class LossTest(val devices: Int, val mode: LossMode, val timeoutSec: Int) {
    init {
        require(devices >= 1) { "loss.devices must be >= 1" }
        require(timeoutSec >= 1) { "loss.timeoutSec must be >= 1" }
    }
}

/**
 * Сценарий прогона (FR-SM-07). [devices] — сколько устройств из кэша учёток запустить; [rampPerSec] — разгон
 * (подключений в секунду, внутри секунды — случайный сдвиг); [warmupSec] — пауза после разгона до окна замера
 * [durationSec]. [telemetryIntervalSec] — интервал телеметрии REST-режима (MQTT берёт интервал из тени, по умолчанию
 * тот же 30 с); [pollIntervalSec] — интервал опроса команд (только REST). [jitter] — доля случайного разброса
 * периодов. [timeScale] — ускорение суточного цикла профилей (1 — реальное время). [network] — метка условий сети
 * для CSV (`baseline`, `latency300`, `loss5`): саму деградацию включает оркестратор (Toxiproxy, `tc netem`).
 */
data class Scenario(
    val name: String,
    val mode: TransportMode,
    val devices: Int,
    val seed: Long = DEFAULT_SEED,
    val rampPerSec: Int = DEFAULT_RAMP_PER_SEC,
    val warmupSec: Int = DEFAULT_WARMUP_SEC,
    val durationSec: Int = DEFAULT_DURATION_SEC,
    val telemetryIntervalSec: Int = DeviceConfig.DEFAULT_INTERVAL_SEC,
    val pollIntervalSec: Int? = null,
    val jitter: Double = DEFAULT_JITTER,
    val timeScale: Double = 1.0,
    val timezone: String = DEFAULT_TIMEZONE,
    val mix: RoleMix = RoleMix(),
    val commands: CommandLoad? = null,
    val loss: LossTest? = null,
    val network: String = "baseline",
) {
    init {
        require(NAME.matches(name)) { "name must match ${NAME.pattern}" }
        require(devices >= 1) { "devices must be >= 1" }
        require(rampPerSec >= 1) { "rampPerSec must be >= 1" }
        require(warmupSec >= 0 && durationSec >= 1) { "warmupSec must be >= 0, durationSec >= 1" }
        require(telemetryIntervalSec >= 1) { "telemetryIntervalSec must be >= 1" }
        require(mode == TransportMode.MQTT || (pollIntervalSec ?: 0) >= 1) { "REST mode needs pollIntervalSec >= 1" }
        require(jitter in 0.0..MAX_JITTER) { "jitter must be in 0..$MAX_JITTER" }
        require(timeScale > 0.0) { "timeScale must be > 0" }
        require(loss == null || loss.devices < devices) { "loss.devices must be less than devices" }
    }

    companion object {
        const val DEFAULT_SEED = 42L
        const val DEFAULT_RAMP_PER_SEC = 100
        const val DEFAULT_WARMUP_SEC = 30
        const val DEFAULT_DURATION_SEC = 240
        const val DEFAULT_JITTER = 0.05
        const val DEFAULT_TIMEZONE = "Europe/Moscow"
        private const val MAX_JITTER = 0.5
        private val NAME = Regex("^[A-Za-z0-9._-]{1,64}$")
    }
}
