package ru.restofleet.agent

import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
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
import org.robolectric.annotation.Config
import ru.restofleet.agent.data.lifecycle.LifecycleEntry
import ru.restofleet.agent.data.lifecycle.LifecycleJournal
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    private class Throwing(base: Context, private val error: RuntimeException) : ContextWrapper(base) {
        override fun startForegroundService(service: Intent?): ComponentName? = throw error
    }

    private fun start(error: RuntimeException, j: LifecycleJournal = journal) =
        AndroidServiceStarter(Throwing(app, error), j).ensureRunning("watchdog")

    @Test
    fun backgroundStartDenialIsJournaledAsDenied() {
        start(ForegroundServiceStartNotAllowedException("not allowed"))

        assertThat(journal.entries())
            .containsExactly(LifecycleEntry(LifecycleEntry.Kind.START_DENIED, 42L, "watchdog"))
        assertThat(journal.lastWatchdogDeniedAt()).isEqualTo(42L)
    }

    @Test
    fun otherIllegalStateIsJournaledAsFailedWithClassName() {
        start(IllegalStateException("boom"))

        assertThat(journal.entries().single())
            .isEqualTo(LifecycleEntry(LifecycleEntry.Kind.START_FAILED, 42L, "watchdog:IllegalStateException"))
    }

    @Test
    fun securityExceptionIsJournaledAsFailedAndDoesNotCrash() {
        start(SecurityException("denied"))

        assertThat(journal.entries().single().reason).isEqualTo("watchdog:SecurityException")
    }

    @Test
    fun brokenJournalInsideCatchDoesNotCrashTheProcess() {
        val broken = LifecycleJournal(tmp.newFolder("dir-as-file"), { 1L })

        start(ForegroundServiceStartNotAllowedException("not allowed"), broken)
    }
}
