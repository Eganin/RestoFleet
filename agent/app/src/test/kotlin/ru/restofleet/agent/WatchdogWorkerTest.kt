package ru.restofleet.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.WorkerFactory
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.restofleet.agent.data.lifecycle.ServiceStarter

@RunWith(RobolectricTestRunner::class)
class WatchdogWorkerTest {
    @get:Rule val workManagerIsolation = WorkManagerIsolationRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun doWorkEnsuresServiceRunningWithWatchdogReasonAndSucceeds() {
        val starter = mockk<ServiceStarter>(relaxed = true)
        val worker = TestListenableWorkerBuilder<WatchdogWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = WatchdogWorker(appContext, workerParameters, starter)
            })
            .build()

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        verify(exactly = 1) { starter.ensureRunning("watchdog") }
    }
}
