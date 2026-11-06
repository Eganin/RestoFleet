package ru.restofleet.agent

import android.app.Application
import androidx.work.WorkManager
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin
import ru.restofleet.agent.di.appModule

class RestoFleetApp : Application(), KoinComponent {
    private val workManager: WorkManager by inject()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@RestoFleetApp)
            workManagerFactory()
            modules(appModule)
        }
        WatchdogScheduler.schedule(workManager)
    }
}
