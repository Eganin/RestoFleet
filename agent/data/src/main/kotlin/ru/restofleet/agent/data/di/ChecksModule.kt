package ru.restofleet.agent.data.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.agent.data.collectors.AppVersionsCollector
import ru.restofleet.agent.data.collectors.BatteryCollector
import ru.restofleet.agent.data.collectors.ForegroundAppCollector
import ru.restofleet.agent.data.collectors.LightSensorCollector
import ru.restofleet.agent.data.collectors.MotionCollector
import ru.restofleet.agent.data.collectors.NetworkChangeObserver
import ru.restofleet.agent.data.collectors.NetworkCollector
import ru.restofleet.agent.data.collectors.PackageEventsObserver
import ru.restofleet.agent.data.collectors.ReadingCheck
import ru.restofleet.agent.data.collectors.ResourceCollector
import ru.restofleet.agent.data.collectors.SsidCollector
import ru.restofleet.agent.data.collectors.SubscriptionCheck
import ru.restofleet.agent.data.collectors.ThermalCollector
import ru.restofleet.agent.data.platform.FreeFallDetector
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason
import ru.restofleet.shared.kernel.Clock

/** Пакеты, версии и события которых проверяют R7 и R12. */
private val WATCHED = listOf("com.android.chrome", "ru.restofleet.dummy.waiter")

/** Qualifier списка проверок R1–R9, R11, R12, R14. */
val READING_CHECKS = named("readingChecks")

/** Проверки считываний Capability Lab. Требует привязанный `Clock` и `androidContext`. */
@Suppress("LongMethod") // декларативный список проверок
val checksModule: Module = module {
    single { BatteryCollector(androidContext()) }
    single { ThermalCollector(androidContext()) }
    single { NetworkCollector(androidContext()) }
    single { SsidCollector(androidContext()) }
    single { ResourceCollector(androidContext()) }
    single { AppVersionsCollector(androidContext()) }
    single { ForegroundAppCollector(androidContext(), get()) }
    single { LightSensorCollector(androidContext()) }
    single { MotionCollector(androidContext(), FreeFallDetector()) }
    single { PackageEventsObserver(androidContext()) }
    single { NetworkChangeObserver(androidContext()) }

    single<List<CapabilityCheck>>(READING_CHECKS) {
        val clock = get<Clock>()
        val battery = get<BatteryCollector>()
        val resources = get<ResourceCollector>()
        val motion = get<MotionCollector>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        listOf(
            ReadingCheck(CheckId.R1, clock, { battery.read() }) {
                mapOf("level" to "${it.levelPct}", "charging" to "${it.charging}", "source" to "${it.source}")
            },
            ReadingCheck(CheckId.R2, clock, {
                when (val reading = battery.read()) {
                    is Reading.Available -> reading.value.tempC?.let { Reading.Available(it) }
                        ?: Reading.Unavailable(UnavailableReason.Failed("no temperature in broadcast"))
                    is Reading.Unavailable -> reading
                }
            }) { mapOf("tempC" to "$it") },
            get<ThermalCollector>().let { thermal ->
                ReadingCheck(CheckId.R3, clock, { thermal.read() }) {
                    mapOf("status" to "${it.status}", "headroom" to "${it.headroom}")
                }
            },
            get<NetworkCollector>().let { network ->
                ReadingCheck(CheckId.R4, clock, { network.read() }) {
                    mapOf("type" to "${it.type}", "rssiDbm" to "${it.rssiDbm}", "validated" to "${it.validated}")
                }
            },
            get<SsidCollector>().let { ssid ->
                ReadingCheck(CheckId.R5, clock, { ssid.read() }) { mapOf("ssid" to it) }
            },
            ReadingCheck(CheckId.R6, clock, { resources.read() }) {
                mapOf(
                    "freeStorageMb" to "${it.freeStorageMb}",
                    "availMemoryMb" to "${it.availMemoryMb}",
                    "uptimeSec" to "${it.uptimeSec}",
                    "os" to it.osVersion,
                    "api" to "${it.apiLevel}",
                    "model" to it.model,
                    "manufacturer" to it.manufacturer,
                )
            },
            get<AppVersionsCollector>().let { apps ->
                ReadingCheck(CheckId.R7, clock, { apps.read(WATCHED) }) { list ->
                    WATCHED.associateWith { pkg ->
                        list.firstOrNull { it.pkg == pkg }?.let { "${it.versionName} (${it.versionCode})" }
                            ?: "not installed"
                    }
                }
            },
            get<ForegroundAppCollector>().let { fg ->
                ReadingCheck(CheckId.R8, clock, { fg.read() }) {
                    mapOf("pkg" to it.pkg, "since" to "${it.sinceMillis}")
                }
            },
            get<LightSensorCollector>().let { light ->
                ReadingCheck(CheckId.R9, clock, { light.read() }) { mapOf("lux" to "${it.lux}") }
            },
            ReadingCheck(CheckId.R11, clock, {
                val significant = motion.hasSignificantMotion()
                val accelerometer = motion.hasAccelerometer()
                if (significant && accelerometer) {
                    Reading.Available(mapOf("significantMotion" to "true", "accelerometer" to "true"))
                } else {
                    Reading.Unavailable(UnavailableReason.NoSensor)
                }
            }) { it },
            SubscriptionCheck(CheckId.R12, clock, get<PackageEventsObserver>().events(WATCHED.toSet()), scope) {
                "${it.pkg} ${it.change} ${it.versionCode}"
            },
            SubscriptionCheck(CheckId.R14, clock, get<NetworkChangeObserver>().changes(), scope) {
                "${it.type} validated=${it.validated}"
            },
        )
    }
}
