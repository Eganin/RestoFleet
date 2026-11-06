package ru.restofleet.agent.data.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.agent.data.collectors.AndroidTelemetrySource
import ru.restofleet.agent.data.collectors.CapabilityChecksFactory
import ru.restofleet.agent.data.lifecycle.AndroidWizardPermissions
import ru.restofleet.agent.data.lifecycle.HeartbeatLog
import ru.restofleet.agent.data.lifecycle.InMemorySequenceSource
import ru.restofleet.agent.data.lifecycle.LifecycleChecks
import ru.restofleet.agent.data.lifecycle.LifecycleJournal
import ru.restofleet.agent.data.lifecycle.TelemetryLoop
import ru.restofleet.agent.data.lifecycle.TelemetryTick
import ru.restofleet.agent.data.lifecycle.WizardPermissions
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.port.SequenceSource
import ru.restofleet.agent.domain.port.TelemetrySource
import ru.restofleet.agent.domain.usecase.CollectTelemetry
import java.io.File

/** Qualifier списка проверок жизненного цикла L1–L4. */
val LIFECYCLE_CHECKS = named("lifecycleChecks")

/** Интервал тика телеметрии (P7: keepalive 30 с). */
const val TELEMETRY_INTERVAL_MS = 30_000L

/**
 * Проводка Koin для цикла агента и проверок L1–L4; логика — в классах пакета `lifecycle`.
 * Нужны извне: `Clock`, `WatchdogSchedule` и коллекторы из [checksModule].
 */
val lifecycleModule: Module = module {
    single { HeartbeatLog(File(androidContext().noBackupFilesDir, "heartbeat.log"), get()) }
    single { LifecycleJournal(File(androidContext().noBackupFilesDir, "lifecycle.log"), get()) }
    single<WizardPermissions> { AndroidWizardPermissions(androidContext()) }
    single<SequenceSource> { InMemorySequenceSource() }
    single<TelemetrySource> { AndroidTelemetrySource(get(), get(), get(), get(), get(), get()) }
    single { CollectTelemetry(get(), get(), get()) }
    single { TelemetryTick(get(), get(), CapabilityChecksFactory.DEFAULT_WATCHED) }
    single { TelemetryLoop(TELEMETRY_INTERVAL_MS, tick = { get<TelemetryTick>().invoke() }) }
    single<List<CapabilityCheck>>(LIFECYCLE_CHECKS) { LifecycleChecks(get(), get(), get(), get(), get()).create() }
}
