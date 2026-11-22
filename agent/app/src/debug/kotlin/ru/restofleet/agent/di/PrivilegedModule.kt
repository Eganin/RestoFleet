package ru.restofleet.agent.di

import org.koin.core.module.Module
import org.koin.dsl.module
import ru.restofleet.agent.DebugDeviceOwnerTab
import ru.restofleet.agent.DebugLabScreen
import ru.restofleet.agent.LabScreen
import ru.restofleet.agent.DeviceOwnerTab
import ru.restofleet.agent.data.di.AGENT_DISPATCHER
import ru.restofleet.agent.data.di.debugToolsModule
import ru.restofleet.agent.domain.port.ForeignTopicPublisher
import ru.restofleet.agent.domain.usecase.FetchApkChecksum
import ru.restofleet.agent.domain.usecase.GenerateDebugEvents
import ru.restofleet.agent.domain.usecase.GrantSelfPermissions
import ru.restofleet.agent.domain.usecase.RunCommandCheck
import ru.restofleet.shared.kernel.UuidIdGenerator
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerViewModel
import ru.restofleet.agent.presentation.lab.viewmodel.LabViewModel

/**
 * Только debug (решение 140): привилегированная поверхность техника — Capability Lab (с КМ3 — только debug, открывается
 * из мастера и статуса, реш. 275), экран Device Owner (тихая установка APK по URL, киоск, перезагрузка), ручной запуск
 * команд C1–C4 в Lab, самовыдача разрешений, подтягивание хэша. Release получает пустой одноимённый модуль из
 * `src/release`. Исполнитель `ExecuteCommand` остаётся в `appModule`: им пользуется приём команд по MQTT.
 */
val privilegedModule: Module = module {
    includes(debugToolsModule)
    single { GenerateDebugEvents(get(), get(), UuidIdGenerator()) }
    single { RunCommandCheck(get(), get(), get()) }
    single { GrantSelfPermissions(get(), get(), get(), get()) }
    single { FetchApkChecksum(get()) }
    factory { DeviceOwnerViewModel(get(), get(), get(), get(), get(), get(), get(AGENT_DISPATCHER)) }
    single<DeviceOwnerTab> { DebugDeviceOwnerTab { get() } }
    factory {
        LabViewModel(
            runChecks = get(),
            exportReport = get(),
            publishResult = get(),
            getDeviceInfo = get(),
            runCommand = get<RunCommandCheck>(),
            debugEvents = get<GenerateDebugEvents>(),
            foreignTopic = get<ForeignTopicPublisher>(),
            io = get(AGENT_DISPATCHER),
        )
    }
    single<LabScreen> { DebugLabScreen({ get() }, get()) }
}
