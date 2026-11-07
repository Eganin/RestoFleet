package ru.restofleet.agent.di

import android.content.ComponentName
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.agent.AdminReceiver
import ru.restofleet.agent.AgentNotifications
import ru.restofleet.agent.AndroidServiceStarter
import ru.restofleet.agent.ForegroundStarter
import ru.restofleet.agent.LockTaskActivity
import ru.restofleet.agent.ServiceCompatForegroundStarter
import ru.restofleet.agent.SettingsIntents
import ru.restofleet.agent.WatchdogWorker
import ru.restofleet.agent.BuildConfig
import ru.restofleet.agent.WorkManagerWatchdogSchedule
import ru.restofleet.agent.data.device.AndroidDeviceInfoSource
import ru.restofleet.agent.data.di.ADMIN_COMPONENT
import ru.restofleet.agent.data.di.LIFECYCLE_CHECKS
import ru.restofleet.agent.data.di.PROBE_CHECKS
import ru.restofleet.agent.data.di.READING_CHECKS
import ru.restofleet.agent.data.deviceadmin.LegacyLockTaskEntry
import ru.restofleet.agent.data.di.checksModule
import ru.restofleet.agent.data.di.commandModule
import ru.restofleet.agent.data.di.deviceAdminModule
import ru.restofleet.agent.data.di.lifecycleModule
import ru.restofleet.agent.data.lifecycle.ServiceStarter
import ru.restofleet.agent.data.lifecycle.WatchdogSchedule
import ru.restofleet.agent.data.report.JsonCapabilityReportWriter
import ru.restofleet.agent.data.report.NotConfiguredCheckResultPublisher
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.port.CapabilityReportWriter
import ru.restofleet.agent.domain.port.CheckResultPublisher
import ru.restofleet.agent.domain.port.DeviceInfoSource
import ru.restofleet.agent.domain.usecase.ExecuteCommand
import ru.restofleet.agent.domain.usecase.ExportCapabilityReport
import ru.restofleet.agent.domain.usecase.GetDeviceInfo
import ru.restofleet.agent.domain.usecase.PublishCheckResult
import ru.restofleet.agent.domain.usecase.RunCapabilityChecks
import ru.restofleet.agent.presentation.lab.viewmodel.LabViewModel
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.SystemClock

/** Диспетчер цикла телеметрии: весь тик (файловый I/O, сборщики) — вне главного потока. */
val AGENT_DISPATCHER = named("agentDispatcher")

/** Composition root агента: платформенные части `agent:app` + модули `agent:data`. */
val appModule: Module = module {
    includes(checksModule, lifecycleModule, deviceAdminModule, commandModule)

    single<Clock> { SystemClock }
    single<CoroutineDispatcher>(AGENT_DISPATCHER) { Dispatchers.IO }
    single { WorkManager.getInstance(androidContext()) }
    single<WatchdogSchedule> { WorkManagerWatchdogSchedule(get()) }
    single<ServiceStarter> { AndroidServiceStarter(androidContext(), get()) }
    single { AgentNotifications(androidContext()) }
    single<ForegroundStarter> { ServiceCompatForegroundStarter() }
    single { SettingsIntents(androidContext().packageName) }
    workerOf(::WatchdogWorker)

    // Device Owner: компонент администратора и вход lock task для API 26–27 — классы app, нужные адаптеру из data
    single(ADMIN_COMPONENT) { ComponentName(androidContext(), AdminReceiver::class.java) }
    single { LegacyLockTaskEntry { pkg, url -> LockTaskActivity.intent(androidContext(), pkg, url) } }

    // Capability Lab: все проверки каталога (L, R, P; остальные группы пока дают NOT_CHECKED), отчёт и публикация
    single<DeviceInfoSource> { AndroidDeviceInfoSource(androidContext(), BuildConfig.VERSION_NAME) }
    single<CapabilityReportWriter> { JsonCapabilityReportWriter.forContext(androidContext(), get(AGENT_DISPATCHER)) }
    // R-M1: до задачи 3.6 публикация отвечает «MQTT не настроен»; привязка заменяется на MQTT-клиент
    single<CheckResultPublisher> { NotConfiguredCheckResultPublisher() }
    single {
        val checks = get<List<CapabilityCheck>>(LIFECYCLE_CHECKS) +
            get<List<CapabilityCheck>>(READING_CHECKS) +
            get<List<CapabilityCheck>>(PROBE_CHECKS)
        RunCapabilityChecks(checks, get())
    }
    single { ExportCapabilityReport(get(), get(), get(), get()) }
    // Исполнение команд: порты из commandModule и deviceAdminModule (сеть команд — M3)
    single { ExecuteCommand(get(), get(), get(), get(), get(), get()) }
    single { GetDeviceInfo(get()) }
    single { PublishCheckResult(get()) }
    factory { LabViewModel(get(), get(), get(), get(), get(AGENT_DISPATCHER)) }
}
