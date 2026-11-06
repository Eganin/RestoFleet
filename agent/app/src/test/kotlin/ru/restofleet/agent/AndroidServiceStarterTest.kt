package ru.restofleet.agent

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import ru.restofleet.agent.data.lifecycle.LifecycleEntry
import ru.restofleet.agent.data.lifecycle.LifecycleJournal
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AndroidServiceStarterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val journal by lazy { LifecycleJournal(File(tmp.root, "j"), { 42L }) }

    @Test
    fun startsAgentServiceWithReasonExtra() {
        AndroidServiceStarter(app, journal).ensureRunning("manual")

        val started = shadowOf(app as android.app.Application).nextStartedService
        assertThat(started.component?.className).isEqualTo(AgentService::class.java.name)
        assertThat(started.getStringExtra(AgentService.EXTRA_REASON)).isEqualTo("manual")
        assertThat(journal.entries()).isEmpty()
    }

    private class Denying(base: Context) : ContextWrapper(base) {
        override fun startForegroundService(service: Intent?) = throw IllegalStateException("not allowed")
    }

    @Test
    fun backgroundStartDenialIsJournaledAndDoesNotThrow() {
        AndroidServiceStarter(Denying(app), journal).ensureRunning("watchdog")

        assertThat(journal.entries()).containsExactly(LifecycleEntry(LifecycleEntry.Kind.START_DENIED, 42L, "watchdog"))
        assertThat(journal.lastWatchdogDeniedAt()).isEqualTo(42L)
    }
}
