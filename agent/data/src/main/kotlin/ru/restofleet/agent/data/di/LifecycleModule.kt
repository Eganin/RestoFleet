package ru.restofleet.agent.data.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.agent.data.collectors.AndroidTelemetrySource
import ru.restofleet.agent.data.collectors.CapabilityChecksFactory
import ru.restofleet.agent.data.credentials.CredentialsCheck
import ru.restofleet.agent.data.lifecycle.AndroidCpuWakeLock
import ru.restofleet.agent.data.lifecycle.AndroidWizardPermissions
import ru.restofleet.agent.data.lifecycle.CpuWakeLock
import ru.restofleet.agent.data.lifecycle.DevicePermissions
import ru.restofleet.agent.data.lifecycle.InMemorySequenceSource
import ru.restofleet.agent.data.lifecycle.LifecycleChecks
import ru.restofleet.agent.data.lifecycle.LifecycleStorage
import ru.restofleet.agent.data.lifecycle.TelemetryLoop
import ru.restofleet.agent.data.lifecycle.TelemetryTick
import ru.restofleet.agent.data.lifecycle.WizardPermissions
import ru.restofleet.agent.data.shadow.AppliedTelemetryInterval
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.port.SequenceSource
import ru.restofleet.agent.domain.port.TelemetrySource
import ru.restofleet.agent.domain.usecase.CollectTelemetry
import ru.restofleet.agent.domain.usecase.EvaluateThresholds
import ru.restofleet.agent.domain.usecase.PublishTelemetry

/** Qualifier списка проверок жизненного цикла L1–L5. */
val LIFECYCLE_CHECKS = named("lifecycleChecks")

/**
 * Проводка Koin для цикла агента и проверок L1–L5; логика — в классах пакета `lifecycle`.
 * Нужны извне: `Clock`, `WatchdogSchedule` и коллекторы из [checksModule].
 */
val lifecycleModule: Module = module {
    single { LifecycleStorage.forContext(androidContext(), get()) }
    single { get<LifecycleStorage>().heartbeatLog() }
    single { get<LifecycleStorage>().journal() }
    single<DevicePermissions> { AndroidWizardPermissions(androidContext()) }
    single<WizardPermissions> { get<DevicePermissions>() }
    single<CpuWakeLock> { AndroidCpuWakeLock(androidContext()) }
    single<SequenceSource> { InMemorySequenceSource() }
    single<TelemetrySource> { AndroidTelemetrySource(get(), get(), get(), get(), get(), get()) }
    single { CollectTelemetry(get(), get(), get()) }
    // Публикация телеметрии (MQTT) подключена лениво: use case берётся из mqttModule на каждом тике
    // После публикации тик проверяет пороги и передний план (EvaluateThresholds, FR-AG-40/41) — тоже из mqttModule
    single {
        TelemetryTick(
            get(),
            get(),
            CapabilityChecksFactory.DEFAULT_WATCHED,
            publish = { get<PublishTelemetry>()(it) },
            evaluate = { get<EvaluateThresholds>()(it) },
        )
    }
    // Интервал берётся из сохранённой тени на каждом тике, пока тени нет — TELEMETRY_INTERVAL_MS
    single {
        TelemetryLoop(interval = { get<AppliedTelemetryInterval>().millis() }, tick = { get<TelemetryTick>().invoke() })
    }
    single<List<CapabilityCheck>>(LIFECYCLE_CHECKS) {
        LifecycleChecks(get(), get(), get(), get(), get(), get()).create() + CredentialsCheck(get(), get())
    }
}
