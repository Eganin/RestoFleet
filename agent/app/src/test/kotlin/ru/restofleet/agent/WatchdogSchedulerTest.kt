package ru.restofleet.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class WatchdogSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        workManager = WorkManager.getInstance(context)
    }

    private fun infos() = workManager.getWorkInfosForUniqueWork(WatchdogScheduler.WORK_NAME).get()

    @Test
    fun schedulesOnePeriodicRequestEvery15Minutes() {
        WatchdogScheduler.schedule(workManager)

        val info = infos().single()
        assertThat(info.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(info.periodicityInfo!!.repeatIntervalMillis).isEqualTo(TimeUnit.MINUTES.toMillis(15))
        assertThat(info.tags).contains(WatchdogWorker::class.java.name)
    }

    @Test
    fun repeatedScheduleKeepsTheExistingRequest() {
        WatchdogScheduler.schedule(workManager)
        val first = infos().single().id

        WatchdogScheduler.schedule(workManager)

        assertThat(infos().single().id).isEqualTo(first)
    }

    @Test
    fun scheduleProbeSeesEnqueuedWorkAndNotCancelled() = runBlocking {
        val probe = WorkManagerWatchdogSchedule(workManager)
        assertThat(probe.isScheduled()).isFalse()

        WatchdogScheduler.schedule(workManager)
        assertThat(probe.isScheduled()).isTrue()

        workManager.cancelUniqueWork(WatchdogScheduler.WORK_NAME).result.get(5, TimeUnit.SECONDS)
        assertThat(probe.isScheduled()).isFalse()
    }
}
