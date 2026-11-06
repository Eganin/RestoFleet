package ru.restofleet.agent

import android.content.Intent
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.agent.data.lifecycle.LifecycleJournal
import ru.restofleet.agent.data.lifecycle.ServiceStarter
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = RestoFleetApp::class)
class BootReceiverTest {
    @get:Rule val tmp = TemporaryFolder()

    private val reasons = mutableListOf<String>()
    private lateinit var journal: LifecycleJournal

    @Before
    fun setUp() {
        journal = LifecycleJournal(File(tmp.root, "j"), { 77L })
        loadKoinModules(
            module {
                single { journal }
                single<ServiceStarter> { ServiceStarter { reasons += it } }
            },
        )
    }

    @After
    fun tearDown() = stopKoin()

    private fun receive(action: String?) =
        BootReceiver().onReceive(ApplicationProvider.getApplicationContext(), Intent(action))

    @Test
    fun bootCompletedRecordsBootAndStartsWithBootReason() {
        receive(Intent.ACTION_BOOT_COMPLETED)

        assertThat(reasons).containsExactly("boot")
        assertThat(journal.lastBootAt()).isEqualTo(77L)
    }

    @Test
    fun packageReplacedStartsWithoutRecordingBoot() {
        receive(Intent.ACTION_MY_PACKAGE_REPLACED)

        assertThat(reasons).containsExactly("package_replaced")
        assertThat(journal.lastBootAt()).isNull()
    }

    @Test
    fun otherActionsAreIgnored() {
        receive(Intent.ACTION_SCREEN_ON)
        receive("com.evil.FAKE_BOOT")
        receive(null)

        assertThat(reasons).isEmpty()
        assertThat(journal.entries()).isEmpty()
    }

    @Test
    fun brokenJournalDoesNotPreventAutostart() {
        loadKoinModules(module { single { LifecycleJournal(tmp.newFolder("dir-as-file"), { 1L }) } })

        receive(Intent.ACTION_BOOT_COMPLETED)

        assertThat(reasons).containsExactly("boot")
    }
}
