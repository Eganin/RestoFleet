package ru.restofleet.agent.data.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import ru.restofleet.agent.data.command.AlarmBeeper
import ru.restofleet.agent.data.command.AndroidAppLauncher
import ru.restofleet.agent.data.command.AndroidCapabilitiesSource
import ru.restofleet.agent.data.command.NotificationUserNotifier
import ru.restofleet.agent.domain.port.AppLauncher
import ru.restofleet.agent.domain.port.Beeper
import ru.restofleet.agent.domain.port.CapabilitiesSource
import ru.restofleet.agent.domain.port.UserNotifier

/**
 * Проводка Koin портов команд обычного режима C1–C4; логики здесь нет. Нужны извне: `DeviceOwnerRole` и
 * `DevicePermissions` (из `checksModule` и `lifecycleModule`).
 */
val commandModule: Module = module {
    single<CapabilitiesSource> { AndroidCapabilitiesSource(get(), get()) }
    single<UserNotifier> { NotificationUserNotifier(androidContext(), get()) }
    single<AppLauncher> { AndroidAppLauncher(androidContext()) }
    // Собственный scope адаптера: остановка сигнала не зависит от вызывающей корутины
    single<Beeper> { AlarmBeeper(androidContext(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
}
