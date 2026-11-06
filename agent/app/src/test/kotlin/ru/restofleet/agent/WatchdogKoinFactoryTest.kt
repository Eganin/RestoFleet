package ru.restofleet.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.core.context.loadKoinModules
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.agent.data.lifecycle.ServiceStarter

/** Доказывает, что в рантайме сторож создаётся Koin-фабрикой (стандартный инициализатор WorkManager удалён). */
@RunWith(RobolectricTestRunner::class)
@Config(application = RestoFleetApp::class)
class WatchdogKoinFactoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val reasons = mutableListOf<String>()

    @After
    fun tearDown() = stopKoin()

    private fun fakeStarter() = loadKoinModules(module { single<ServiceStarter> { ServiceStarter { reasons += it } } })

    @Test
    fun koinWorkerFactoryBuildsWatchdogWorkerWithDependencies() {
        fakeStarter()
        val worker = TestListenableWorkerBuilder<WatchdogWorker>(context).setWorkerFactory(KoinWorkerFactory()).build()

        assertThat(worker).isInstanceOf(WatchdogWorker::class.java)
        assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(reasons).containsExactly("watchdog")
    }

    @Test
    fun workManagerOfTheRunningAppIsConfiguredWithTheKoinFactory() {
        fakeStarter()
        val appFactory = WorkManager.getInstance(context).configuration.workerFactory

        val worker = TestListenableWorkerBuilder<WatchdogWorker>(context).setWorkerFactory(appFactory).build()

        assertThat(worker).isInstanceOf(WatchdogWorker::class.java)
        assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(reasons).containsExactly("watchdog")
    }
}
