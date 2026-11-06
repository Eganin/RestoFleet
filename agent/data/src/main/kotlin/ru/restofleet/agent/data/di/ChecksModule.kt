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
import ru.restofleet.agent.data.collectors.CapabilityChecksFactory
import ru.restofleet.agent.data.collectors.ForegroundAppCollector
import ru.restofleet.agent.data.collectors.LightSensorCollector
import ru.restofleet.agent.data.collectors.MotionCollector
import ru.restofleet.agent.data.collectors.NetworkChangeObserver
import ru.restofleet.agent.data.collectors.NetworkCollector
import ru.restofleet.agent.data.collectors.PackageEventsObserver
import ru.restofleet.agent.data.collectors.ResourceCollector
import ru.restofleet.agent.data.collectors.SsidCollector
import ru.restofleet.agent.data.collectors.ThermalCollector
import ru.restofleet.agent.data.platform.FreeFallDetector
import ru.restofleet.agent.domain.capability.CapabilityCheck

/** Qualifier списка проверок R1–R9, R11, R12, R14. */
val READING_CHECKS = named("readingChecks")

/** Проводка Koin для проверок считываний Capability Lab; логика — в [CapabilityChecksFactory]. Нужен `Clock`. */
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
        CapabilityChecksFactory(
            clock = get(),
            battery = get(),
            thermal = get(),
            network = get(),
            ssid = get(),
            resources = get(),
            appVersions = get(),
            foreground = get(),
            light = get(),
            motion = get(),
            packageEvents = get(),
            networkChanges = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ).create()
    }
}
