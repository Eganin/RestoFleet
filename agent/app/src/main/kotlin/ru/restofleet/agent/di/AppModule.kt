package ru.restofleet.agent.di

import androidx.work.WorkManager
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.Module
import org.koin.dsl.module
import ru.restofleet.agent.AgentNotifications
import ru.restofleet.agent.AndroidServiceStarter
import ru.restofleet.agent.SettingsIntents
import ru.restofleet.agent.WatchdogWorker
import ru.restofleet.agent.WorkManagerWatchdogSchedule
import ru.restofleet.agent.data.di.checksModule
import ru.restofleet.agent.data.di.lifecycleModule
import ru.restofleet.agent.data.lifecycle.ServiceStarter
import ru.restofleet.agent.data.lifecycle.WatchdogSchedule
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.SystemClock

/** Composition root агента: платформенные части `agent:app` + модули `agent:data`. */
val appModule: Module = module {
    includes(checksModule, lifecycleModule)

    single<Clock> { SystemClock }
    single { WorkManager.getInstance(androidContext()) }
    single<WatchdogSchedule> { WorkManagerWatchdogSchedule(get()) }
    single<ServiceStarter> { AndroidServiceStarter(androidContext(), get()) }
    single { AgentNotifications(androidContext()) }
    single { SettingsIntents(androidContext().packageName) }
    workerOf(::WatchdogWorker)
}
