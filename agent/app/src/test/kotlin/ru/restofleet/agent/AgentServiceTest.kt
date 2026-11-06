package ru.restofleet.agent

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.restofleet.agent.data.lifecycle.LifecycleEntry
import ru.restofleet.agent.data.lifecycle.LifecycleJournal
import ru.restofleet.agent.data.lifecycle.TelemetryLoop
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = RestoFleetApp::class)
class AgentServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val ticks = AtomicInteger()
    private lateinit var journal: LifecycleJournal

    @Before
    fun setUp() {
        journal = LifecycleJournal(File(tmp.root, "j"), { 1L })
        // Приложение (RestoFleetApp) уже подняло Koin; подменяем тяжёлые части лёгкими.
        loadKoinModules(
            module {
                single { journal }
                single { TelemetryLoop(30_000, { ticks.incrementAndGet() }) }
            },
        )
    }

    @After
    fun tearDown() = stopKoin()

    private fun controller(reason: String? = "boot") = Robolectric.buildService(
        AgentService::class.java,
        Intent(ApplicationProvider.getApplicationContext(), AgentService::class.java).apply {
            reason?.let { putExtra(AgentService.EXTRA_REASON, it) }
        },
    )

    @Test
    fun startCommandGoesForegroundWithSpecialUseTypeAndOngoingNotification() {
        val c = controller().create()
        val result = c.get().onStartCommand(Intent().putExtra(AgentService.EXTRA_REASON, "boot"), 0, 1)

        val shadow = shadowOf(c.get())
        assertThat(result).isEqualTo(android.app.Service.START_STICKY)
        assertThat(shadow.lastForegroundNotificationId).isEqualTo(AgentNotifications.NOTIFICATION_ID)
        assertThat(c.get().foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val n = shadow.lastForegroundNotification
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(Build.VERSION.SDK_INT).isAtLeast(34)
    }

    @Test
    fun cycleStartIsJournaledWithReasonAndRepeatedCommandIsNotAStart() {
        val c = controller().create()
        val service = c.get()
        service.onStartCommand(Intent().putExtra(AgentService.EXTRA_REASON, "boot"), 0, 1)
        service.onStartCommand(Intent().putExtra(AgentService.EXTRA_REASON, "watchdog"), 0, 2)

        assertThat(journal.entries().map { it.kind to it.reason })
            .containsExactly(LifecycleEntry.Kind.START to "boot")
    }

    @Test
    fun nullIntentFromStickyRestartHasSystemRestartReason() {
        val c = controller().create()
        c.get().onStartCommand(null, 0, 1)

        assertThat(journal.entries().single().reason).isEqualTo(AgentService.REASON_SYSTEM_RESTART)
    }

    @Test
    fun destroyStopsTheCycleAndJournalsStop() {
        val c = controller().create()
        c.get().onStartCommand(Intent().putExtra(AgentService.EXTRA_REASON, "manual"), 0, 1)

        c.destroy()

        assertThat(journal.entries().last().kind).isEqualTo(LifecycleEntry.Kind.STOP)
    }

    @Test
    fun notificationChannelIsLowImportance() {
        val c = controller().create()
        c.get().onStartCommand(Intent(), 0, 1)

        val nm = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.app.NotificationManager::class.java)
        val channel = nm.getNotificationChannel(AgentNotifications.CHANNEL_ID)
        assertThat(channel.importance).isEqualTo(android.app.NotificationManager.IMPORTANCE_LOW)
    }
}
