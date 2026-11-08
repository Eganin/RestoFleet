package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.capability.Permission
import ru.restofleet.agent.domain.command.Command
import ru.restofleet.agent.domain.command.Level
import ru.restofleet.agent.domain.fake.FakeAppLauncher
import ru.restofleet.agent.domain.fake.FakeBeeper
import ru.restofleet.agent.domain.fake.FakeCheckResultJournal
import ru.restofleet.agent.domain.fake.FakeDeviceAdmin
import ru.restofleet.agent.domain.fake.FakeUserNotifier
import ru.restofleet.agent.domain.fake.TestError
import ru.restofleet.agent.domain.port.CapabilitiesSource
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

class RunCommandCheckTest {
    private var caps = Capabilities(AgentMode.NORMAL, 34, setOf(Permission.POST_NOTIFICATIONS))
    private val admin = FakeDeviceAdmin()
    private val beeper = FakeBeeper()
    private val notifier = FakeUserNotifier()
    private val launcher = FakeAppLauncher()
    private val journal = FakeCheckResultJournal()
    private val check = RunCommandCheck(
        ExecuteCommand(CapabilitiesSource { caps }, admin, notifier, launcher, beeper, Clock { NOW }),
        journal,
        Clock { NOW },
    )

    @Test
    fun succeededWithResultIsOkAndKeepsTheResultMap() = runTest {
        beeper.result = { Outcome.Success(mapOf("ringerMode" to "silent")) }

        val result = check(CheckId.C4, Command.Beep(3_000))

        assertThat(result)
            .isEqualTo(CheckResult(CheckId.C4, CheckStatus.OK, mapOf("ringerMode" to "silent"), null, NOW))
    }

    @Test
    fun succeededWithEmptyResultIsOkWithNote() = runTest {
        val result = check(CheckId.C1, Command.ShowMessage("Проверка RestoFleet", Level.WARNING))

        assertThat(result.status).isEqualTo(CheckStatus.OK)
        assertThat(result.note).isEqualTo("succeeded")
        assertThat(notifier.calls).hasSize(1)
    }

    @Test
    fun failedIsUnavailableWithReasonAsNote() = runTest {
        beeper.result = { Outcome.Failure(TestError("no_alarm_sound")) }

        val result = check(CheckId.C4, Command.Beep(3_000))

        assertThat(result.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(result.note).isEqualTo("no_alarm_sound")
    }

    @Test
    fun deviceOwnerCommandWithoutRoleIsNotApplicableAndPortIsNotCalled() = runTest {
        val result = check(CheckId.D2, Command.Reboot)

        assertThat(result.status).isEqualTo(CheckStatus.NOT_APPLICABLE)
        assertThat(result.note).isEqualTo("requires_device_owner")
        assertThat(admin.calls).isEmpty()
    }

    @Test
    fun otherUnsupportedReasonsAreUnavailable() = runTest {
        caps = Capabilities(AgentMode.DEVICE_OWNER, 34, emptySet())

        val result = check(CheckId.D3, Command.InstallApk("http://x/a.apk", "a".repeat(64)))

        assertThat(result.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(result.note).isEqualTo("invalid_url")
    }

    @Test
    fun everyResultIsRecordedInTheJournalUnderItsId() = runTest {
        val first = check(CheckId.C4, Command.Beep(3_000))
        val second = check(CheckId.D2, Command.Reboot)

        assertThat(journal.records).containsExactly(first, second).inOrder()
    }

    private companion object {
        const val NOW = 1_700_000_000_123L
    }
}
