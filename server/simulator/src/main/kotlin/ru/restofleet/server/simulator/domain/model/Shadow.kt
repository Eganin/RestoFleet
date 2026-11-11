package ru.restofleet.server.simulator.domain.model

/** Пороги тени (ТЗ 7.2, FR-AG-40, 41). */
data class Thresholds(
    val batteryLowPct: Int = 20,
    val tempHighC: Double = 45.0,
    val fgAbsentMin: Int = 10,
)

/** Цель проверки доступности из `desired.probes` (FR-AG-30): `http` или `tcp`. */
data class ProbeTarget(
    val id: String,
    val type: String,
    val url: String,
    val intervalSec: Int,
    val timeoutMs: Long,
    val failThreshold: Int,
    val reference: Boolean = false,
    val expectedStatus: Int? = null,
)

/** Пришедший `desired` (ТЗ 7.2). Неразборчивый JSON транспорт отбрасывает до домена. */
data class DesiredUpdate(
    val version: Long,
    val telemetryIntervalSec: Int,
    val thresholds: Thresholds,
    val watchedApps: List<String>,
    val probes: List<ProbeTarget>,
)

/** Применённая конфигурация устройства; до первой тени — значения по умолчанию бэкенда (интервал 30 с). */
data class DeviceConfig(
    val version: Long? = null,
    val telemetryIntervalSec: Int = DEFAULT_INTERVAL_SEC,
    val thresholds: Thresholds = Thresholds(),
    val watchedApps: List<String> = emptyList(),
    val probes: List<ProbeTarget> = emptyList(),
) {
    companion object {
        const val DEFAULT_INTERVAL_SEC = 30
    }
}

/** `shadow/reported`: применённая конфигурация, версия и ошибки валидации (ТЗ 7.2). */
data class ReportedShadow(
    val config: DeviceConfig,
    val appliedVersion: Long,
    val appliedAt: Long,
    val errors: List<String>,
)
