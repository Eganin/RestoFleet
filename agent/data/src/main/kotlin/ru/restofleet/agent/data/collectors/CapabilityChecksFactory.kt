package ru.restofleet.agent.data.collectors

import kotlinx.coroutines.CoroutineScope
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.model.AppVersion
import ru.restofleet.agent.domain.model.BatteryState
import ru.restofleet.agent.domain.model.DeviceResources
import ru.restofleet.agent.domain.model.ForegroundApp
import ru.restofleet.agent.domain.model.NetworkState
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.ThermalState
import ru.restofleet.agent.domain.model.UnavailableReason
import ru.restofleet.shared.kernel.Clock

/** Чистые преобразования значений считываний в `raw` отчёта и выводы R2/R11 (тестируются на JVM). */
internal object CheckValues {
    fun batteryTemp(reading: Reading<BatteryState>): Reading<Double> = when (reading) {
        is Reading.Available -> reading.value.tempC?.let { Reading.Available(it) }
            ?: Reading.Unavailable(UnavailableReason.Failed("no temperature in broadcast"))
        is Reading.Unavailable -> reading
    }

    fun batteryRaw(b: BatteryState) =
        mapOf("level" to "${b.levelPct}", "charging" to "${b.charging}", "source" to "${b.source}")

    fun thermalRaw(t: ThermalState) = mapOf("status" to "${t.status}", "headroom" to "${t.headroom}")

    fun networkRaw(n: NetworkState) =
        mapOf("type" to "${n.type}", "rssiDbm" to "${n.rssiDbm}", "validated" to "${n.validated}")

    fun resourcesRaw(r: DeviceResources) = mapOf(
        "freeStorageMb" to "${r.freeStorageMb}",
        "availMemoryMb" to "${r.availMemoryMb}",
        "uptimeSec" to "${r.uptimeSec}",
        "os" to r.osVersion,
        "api" to "${r.apiLevel}",
        "model" to r.model,
        "manufacturer" to r.manufacturer,
    )

    fun appsRaw(watched: List<String>, found: List<AppVersion>): Map<String, String> =
        watched.associateWith { pkg ->
            found.firstOrNull { it.pkg == pkg }?.let { "${it.versionName} (${it.versionCode})" } ?: "not installed"
        }

    fun foregroundRaw(app: ForegroundApp) = mapOf("pkg" to app.pkg, "since" to "${app.sinceMillis}")

    /** R11 доступен, только если есть оба датчика; причина называет отсутствующие. */
    fun motionSupport(significant: Boolean, accelerometer: Boolean): Reading<Map<String, String>> {
        val missing = listOfNotNull(
            "significant_motion".takeUnless { significant },
            "accelerometer".takeUnless { accelerometer },
        )
        return if (missing.isEmpty()) {
            Reading.Available(mapOf("significantMotion" to "true", "accelerometer" to "true"))
        } else {
            Reading.Unavailable(UnavailableReason.Failed("missing sensor: ${missing.joinToString()}"))
        }
    }
}

/** Собирает проверки Lab R1–R9, R11, R12, R14 поверх коллекторов; Koin-модуль только передаёт зависимости. */
@Suppress("LongParameterList") // по одному коллектору на показатель
class CapabilityChecksFactory(
    private val clock: Clock,
    private val battery: BatteryCollector,
    private val thermal: ThermalCollector,
    private val network: NetworkCollector,
    private val ssid: SsidCollector,
    private val resources: ResourceCollector,
    private val appVersions: AppVersionsCollector,
    private val foreground: ForegroundAppCollector,
    private val light: LightSensorCollector,
    private val motion: MotionCollector,
    private val packageEvents: PackageEventsObserver,
    private val networkChanges: NetworkChangeObserver,
    private val scope: CoroutineScope,
    private val watched: List<String> = DEFAULT_WATCHED,
) {
    fun create(): List<CapabilityCheck> = listOf(
        ReadingCheck(CheckId.R1, clock, { battery.read() }, CheckValues::batteryRaw),
        ReadingCheck(CheckId.R2, clock, { CheckValues.batteryTemp(battery.read()) }) { mapOf("tempC" to "$it") },
        ReadingCheck(CheckId.R3, clock, { thermal.read() }, CheckValues::thermalRaw),
        ReadingCheck(CheckId.R4, clock, { network.read() }, CheckValues::networkRaw),
        ReadingCheck(CheckId.R5, clock, { ssid.read() }) { mapOf("ssid" to it) },
        ReadingCheck(CheckId.R6, clock, { resources.read() }, CheckValues::resourcesRaw),
        ReadingCheck(CheckId.R7, clock, { appVersions.read(watched) }) { CheckValues.appsRaw(watched, it) },
        ReadingCheck(CheckId.R8, clock, { foreground.read() }, CheckValues::foregroundRaw),
        ReadingCheck(CheckId.R9, clock, { light.read() }) { mapOf("lux" to "${it.lux}") },
        ReadingCheck(
            CheckId.R11,
            clock,
            { CheckValues.motionSupport(motion.hasSignificantMotion(), motion.hasAccelerometer()) },
        ) { it },
        SubscriptionCheck(CheckId.R12, clock, packageEvents.events(watched.toSet()), scope) {
            "${it.pkg} ${it.change} ${it.versionCode}"
        },
        SubscriptionCheck(CheckId.R14, clock, networkChanges.changes(), scope) {
            "${it.type} validated=${it.validated}"
        },
    )

    companion object {
        /** Должен совпадать с `<queries>` в AndroidManifest.xml. */
        val DEFAULT_WATCHED = listOf("com.android.chrome", "ru.restofleet.dummy.waiter")
    }
}
