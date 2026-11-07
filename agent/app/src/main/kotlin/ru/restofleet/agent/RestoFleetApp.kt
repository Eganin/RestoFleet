package ru.restofleet.agent

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import ru.restofleet.agent.di.appModule

/**
 * WorkManager инициализируется по требованию через [Configuration.Provider] (стандартный инициализатор удалён в
 * манифесте): сторож создаётся Koin-фабрикой. Конфигурация не зависит от того, создан ли WorkManager раньше.
 */
class RestoFleetApp : Application(), KoinComponent, Configuration.Provider {
    private val workManager: WorkManager by inject()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(KoinWorkerFactory()).build()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@RestoFleetApp)
            modules(appModule)
        }
        WatchdogScheduler.schedule(workManager)
    }
}
