package ru.restofleet.agent.di

import android.content.ComponentName
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.Module
import org.koin.dsl.module
import ru.restofleet.agent.AdminReceiver
import ru.restofleet.agent.AgentNotifications
import ru.restofleet.agent.AndroidServiceStarter
import ru.restofleet.agent.ForegroundStarter
import ru.restofleet.agent.LabScreen
import ru.restofleet.agent.LockTaskActivity
import ru.restofleet.agent.ServiceCompatForegroundStarter
import ru.restofleet.agent.StandCaSource
import ru.restofleet.agent.SettingsIntents
import ru.restofleet.agent.WatchdogWorker
import ru.restofleet.agent.BuildConfig
import ru.restofleet.agent.WorkManagerWatchdogSchedule
import ru.restofleet.agent.data.device.AndroidDeviceInfoSource
import ru.restofleet.agent.data.di.ADMIN_COMPONENT
import ru.restofleet.agent.data.di.AGENT_DISPATCHER
import ru.restofleet.agent.data.di.DEVICE_OWNER_CHECKS
import ru.restofleet.agent.data.di.LIFECYCLE_CHECKS
import ru.restofleet.agent.data.di.PROBE_CHECKS
import ru.restofleet.agent.data.di.READING_CHECKS
import ru.restofleet.agent.data.deviceadmin.LegacyLockTaskEntry
import ru.restofleet.agent.data.di.checksModule
import ru.restofleet.agent.data.di.commandModule
import ru.restofleet.agent.data.di.deviceAdminModule
import ru.restofleet.agent.data.di.lifecycleModule
import ru.restofleet.agent.data.di.mqttModule
import ru.restofleet.agent.data.di.provisioningModule
import ru.restofleet.agent.data.di.MQTT_CHECKS
import ru.restofleet.agent.data.di.storageModule
import ru.restofleet.agent.data.lifecycle.ServiceStarter
import ru.restofleet.agent.data.lifecycle.WatchdogSchedule
import ru.restofleet.agent.data.mqtt.CaSource
import ru.restofleet.agent.data.provisioning.ProvisioningTrust
import ru.restofleet.agent.data.report.InMemoryCheckResultJournal
import ru.restofleet.agent.data.report.JsonCapabilityReportWriter
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.report.EventCheckResultPublisher
import ru.restofleet.agent.domain.port.CapabilityReportWriter
import ru.restofleet.agent.domain.port.CheckResultJournal
import ru.restofleet.agent.domain.port.CheckResultPublisher
import ru.restofleet.agent.domain.port.DeviceInfoSource
import ru.restofleet.agent.domain.usecase.ConfigureBrokerCredentials
import ru.restofleet.agent.domain.usecase.ExecuteCommand
import ru.restofleet.agent.domain.usecase.ExportCapabilityReport
import ru.restofleet.agent.domain.usecase.GetDeviceInfo
import ru.restofleet.agent.domain.usecase.PublishCheckResult
import ru.restofleet.agent.domain.usecase.RunCapabilityChecks
import ru.restofleet.agent.presentation.root.viewmodel.RootViewModel
import ru.restofleet.agent.presentation.setup.api.ISetupRouter
import ru.restofleet.agent.presentation.setup.viewmodel.SetupViewModel
import ru.restofleet.agent.presentation.status.api.IStatusRouter
import ru.restofleet.agent.presentation.status.viewmodel.StatusViewModel
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.probes.ProbeSchedule
import ru.restofleet.shared.kernel.SystemClock

/** Composition root агента: платформенные части `agent:app` + модули `agent:data`. */
val appModule: Module = module {
    includes(
        checksModule, lifecycleModule, deviceAdminModule, commandModule, storageModule, mqttModule, provisioningModule,
        privilegedModule,
    )

    single<Clock> { SystemClock }
    // CA стенда для MQTT: debug читает R.raw.stand_ca, release возвращает null (fail closed, решение 3.6a)
    single<CaSource> { StandCaSource(androidContext()) }
    // Провижининг по коду (план 3.5): debug доверяет только CA стенда (как MQTT), release — системному хранилищу
    single<ProvisioningTrust> {
        if (BuildConfig.DEBUG) ProvisioningTrust.PinnedStandCa(get()) else ProvisioningTrust.System
    }
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
    single<DeviceInfoSource> { AndroidDeviceInfoSource(androidContext(), BuildConfig.VERSION_NAME, get()) }
    single<CapabilityReportWriter> { JsonCapabilityReportWriter.forContext(androidContext(), get(AGENT_DISPATCHER)) }
    // Lab -> событие capability_check через PublishOrBuffer (без связи ждёт в outbox)
    single<CheckResultPublisher> { EventCheckResultPublisher(get(), get()) }
    single {
        val checks = get<List<CapabilityCheck>>(LIFECYCLE_CHECKS) +
            get<List<CapabilityCheck>>(READING_CHECKS) +
            get<List<CapabilityCheck>>(PROBE_CHECKS) +
            get<List<CapabilityCheck>>(DEVICE_OWNER_CHECKS) +
            get<List<CapabilityCheck>>(MQTT_CHECKS)
        RunCapabilityChecks(checks, get())
    }
    // Журнал ручных проверок (C1–C4, D2–D6): отчёт Lab берёт из него то, что RunCapabilityChecks не исполняет сам
    single<CheckResultJournal> { InMemoryCheckResultJournal() }
    single { ExportCapabilityReport(get(), get(), get(), get(), get()) }
    // Исполнение команд: порты из commandModule и deviceAdminModule (сеть команд — M3)
    single { ExecuteCommand(get(), get(), get(), get(), get(), get(), onDemand = get()) }
    // Минимальный интервал плановых проб (реш. 228): debug-сборка работает со стендом — 5 с, release — 30 с
    single { if (BuildConfig.DEBUG) ProbeSchedule.forStand() else ProbeSchedule() }
    single { GetDeviceInfo(get()) }
    single { PublishCheckResult(get()) }
    // Приём учётки брокера (debug-ресивер DevConfigReceiver; в release use case просто не вызывается)
    single { ConfigureBrokerCredentials(get()) }
    // Экраны агента (план 3.6): навигация, мастер, статус. Lab и Device Owner — только debug (LabScreen в
    // privilegedModule, реш. 140, 275); роутер приходит параметром — он живёт вместе с ViewModel в InstanceKeeper
    factory { RootViewModel(get(), labAvailable = getOrNull<LabScreen>() != null, io = get(AGENT_DISPATCHER)) }
    factory { params ->
        SetupViewModel(
            getPermissions = get(),
            provision = get(),
            router = params.get<ISetupRouter>(),
            labAvailable = getOrNull<LabScreen>() != null,
            io = get(AGENT_DISPATCHER),
        )
    }
    factory { params ->
        StatusViewModel(
            getStatus = get(),
            router = params.get<IStatusRouter>(),
            labAvailable = getOrNull<LabScreen>() != null,
            io = get(AGENT_DISPATCHER),
        )
    }
}
