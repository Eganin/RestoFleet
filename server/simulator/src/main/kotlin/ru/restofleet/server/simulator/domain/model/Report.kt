package ru.restofleet.server.simulator.domain.model

/** Байты интерфейса устройств (счётчики ядра: IP-пакеты целиком, включая TCP/IP и TLS). */
data class TrafficSnapshot(val rxBytes: Long, val txBytes: Long)

/** Счётчики прогона на момент снимка (накопительные). */
data class CounterSnapshot(
    val connected: Long,
    val connectFailures: Long,
    val connectionLosses: Long,
    val messagesOut: Long,
    val messagesIn: Long,
    val requests: Long,
    val requestErrors: Long,
    val publishErrors: Long,
    val payloadBytesOut: Long,
    val payloadBytesIn: Long,
)

/** Строка временного ряда (раз в 5 с): фаза прогона, счётчики и байты интерфейса. */
data class TimelineRow(
    val wallMillis: Long,
    val phase: String,
    val counters: CounterSnapshot,
    val traffic: TrafficSnapshot?,
)

/** Команда измерителя: отправка (стена, мс), задержка до устройства (мс, по монотонным часам) и итог в бэкенде. */
data class CommandRecord(
    val cmdId: String,
    val deviceId: String,
    val sentAtMillis: Long,
    val latencyMs: Double?,
    val finalStatus: String?,
)

/** Замер обнаружения пропажи одного устройства: [offlineAfterMs] `null` — не увидели `offline` за таймаут. */
data class DetectionRecord(
    val deviceId: String,
    val lostAtMillis: Long,
    val offlineAfterMs: Long?,
    val lastStatus: String?,
)

/** Итог регистрации (FR-SM-08): сколько учёток было, сколько добавлено, отказы и время. */
data class RegistrationReport(
    val requested: Int,
    val cached: Int,
    val registered: Int,
    val failed: Int,
    val durationMs: Long,
    val failures: Map<String, Int>,
)

/** Итог прогона: окно замера [windowStartMillis]…[windowEndMillis] и всё, что в нём измерено. */
data class RunReport(
    val scenario: Scenario,
    val startedAtMillis: Long,
    val rampEndMillis: Long,
    val windowStartMillis: Long,
    val windowEndMillis: Long,
    val connectedAtWindowStart: Long,
    val windowCounters: CounterSnapshot,
    val windowTraffic: TrafficSnapshot?,
    val commands: List<CommandRecord>,
    val commandSendFailures: Int,
    val detections: List<DetectionRecord>,
    val timeline: List<TimelineRow>,
)
