package ru.restofleet.server.simulator.domain.model

import ru.restofleet.server.simulator.domain.fault.DeviceFault
import ru.restofleet.server.simulator.domain.fault.DeviceFaultType
import ru.restofleet.server.simulator.domain.synthetic.EpisodeGenerator
import ru.restofleet.server.simulator.domain.synthetic.Outage
import ru.restofleet.server.simulator.domain.synthetic.ProbeMode

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
    /** `synthetic`: пробы не ходят в сеть, результат задают [outages] и [episodes] (FR-SM-05). */
    val probeMode: ProbeMode = ProbeMode.REAL,
    /** Цели проверок в режиме `synthetic` (по умолчанию `api` и эталон `ref`). */
    val syntheticTargets: List<ProbeTarget> = DEFAULT_SYNTHETIC_TARGETS,
    val faults: List<DeviceFault> = emptyList(),
    val outages: List<Outage> = emptyList(),
    val episodes: EpisodePlan? = null,
    /** Парк ресторанов для `prepare` и отказов по коду (реш. 428): `S8-01…S8-12`. */
    val fleet: FleetSpec? = null,
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
        require(outages.isEmpty() && episodes == null || probeMode == ProbeMode.SYNTHETIC) {
            "outages and episodes need probeMode: synthetic"
        }
    }

    /** Нужны коды ресторанов и управляемые связи (пропажа и возврат устройств во время прогона). */
    val hasDisruptions: Boolean
        get() = outages.isNotEmpty() || episodes != null || faults.any { it.type == DeviceFaultType.MASS_RECONNECT }

    companion object {
        const val DEFAULT_SEED = 42L
        const val DEFAULT_RAMP_PER_SEC = 100
        const val DEFAULT_WARMUP_SEC = 30
        const val DEFAULT_DURATION_SEC = 240
        const val DEFAULT_JITTER = 0.05
        const val DEFAULT_TIMEZONE = "Europe/Moscow"
        private const val SYNTHETIC_INTERVAL_SEC = 15
        private const val SYNTHETIC_TIMEOUT_MS = 5_000L
        private const val SYNTHETIC_FAILS = 3

        /** `api` и эталон `ref` каждые 15 с, K = 3: «недоступна» через ≈ 45 с (минимум стенда — 5 с). */
        val DEFAULT_SYNTHETIC_TARGETS = listOf(
            ProbeTarget(
                "api", "http", "synthetic://api", SYNTHETIC_INTERVAL_SEC, SYNTHETIC_TIMEOUT_MS, SYNTHETIC_FAILS,
            ),
            ProbeTarget(
                "ref", "http", "synthetic://ref", SYNTHETIC_INTERVAL_SEC, SYNTHETIC_TIMEOUT_MS, SYNTHETIC_FAILS,
                reference = true,
            ),
        )
        private const val MAX_JITTER = 0.5
        private val NAME = Regex("^[A-Za-z0-9._-]{1,64}$")
    }
}

/** Раунды генератора эпизодов точности (реш. 428). */
data class EpisodePlan(
    val rounds: Int,
    val roundSec: Int = EpisodeGenerator.DEFAULT_ROUND_SEC,
    val activeFromSec: Int = EpisodeGenerator.DEFAULT_ACTIVE_FROM_SEC,
    val activeSec: Int = EpisodeGenerator.DEFAULT_ACTIVE_SEC,
    val globalShare: Double = EpisodeGenerator.DEFAULT_GLOBAL_SHARE,
) {
    init {
        require(rounds >= 1) { "episodes.rounds must be >= 1" }
        require(globalShare in 0.0..1.0) { "episodes.globalShare must be in 0..1" }
    }
}

/** Парк ресторанов прогона: коды `<prefix>-01…<prefix>-NN`, у каждого [perRestaurant] устройств (реш. 428). */
data class FleetSpec(val prefix: String, val restaurants: Int, val perRestaurant: Int = DEFAULT_PER_RESTAURANT) {
    init {
        require(Regex("^[A-Za-z0-9]{1,16}$").matches(prefix)) { "fleet.prefix must be 1..16 letters or digits" }
        require(restaurants in 1..MAX_RESTAURANTS && perRestaurant >= 1) { "invalid fleet size" }
    }

    val codes: List<String> get() = (1..restaurants).map { "%s-%02d".format(prefix, it) }

    val devices: Int get() = restaurants * perRestaurant

    private companion object {
        const val DEFAULT_PER_RESTAURANT = 6
        const val MAX_RESTAURANTS = 99
    }
}
