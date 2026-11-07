package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.capability.LaunchStrategy
import ru.restofleet.agent.domain.capability.Permission
import ru.restofleet.agent.domain.command.Command
import ru.restofleet.agent.domain.command.CommandOutcome
import ru.restofleet.agent.domain.command.Level
import ru.restofleet.agent.domain.fake.FakeAppLauncher
import ru.restofleet.agent.domain.fake.FakeBeeper
import ru.restofleet.agent.domain.fake.FakeDeviceAdmin
import ru.restofleet.agent.domain.fake.FakeUserNotifier
import ru.restofleet.agent.domain.fake.TestError
import ru.restofleet.agent.domain.port.CapabilitiesSource
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome
import java.util.concurrent.CancellationException
import kotlin.test.assertFailsWith

class ExecuteCommandTest {

    private var caps = Capabilities(AgentMode.NORMAL, 34, setOf(Permission.POST_NOTIFICATIONS))
    private val admin = FakeDeviceAdmin()
    private val notifier = FakeUserNotifier()
    private val launcher = FakeAppLauncher()
    private val beeper = FakeBeeper()
    private val execute = ExecuteCommand(
        caps = CapabilitiesSource { caps },
        admin = admin,
        notifier = notifier,
        launcher = launcher,
        beeper = beeper,
        clock = Clock { 1_700_000_000_123L },
    )
    private val sha = "b".repeat(64)

    private fun deviceOwner() {
        caps = Capabilities(AgentMode.DEVICE_OWNER, 34, emptySet())
    }

    private fun assertNoPortCalls() {
        assertThat(admin.calls).isEmpty()
        assertThat(notifier.calls).isEmpty()
        assertThat(launcher.calls).isEmpty()
        assertThat(beeper.calls).isEmpty()
    }

    @Test
    fun pingReturnsDeviceTime() = runTest {
        assertThat(execute(Command.Ping))
            .isEqualTo(CommandOutcome.Succeeded(mapOf("deviceTime" to "1700000000123")))
    }

    @Test
    fun rebootInNormalModeIsUnsupportedAndAdminNotCalled() = runTest {
        assertThat(execute(Command.Reboot)).isEqualTo(CommandOutcome.Unsupported("requires_device_owner"))
        assertNoPortCalls()
    }

    @Test
    fun rebootInDeviceOwnerSucceeds() = runTest {
        deviceOwner()

        assertThat(execute(Command.Reboot)).isEqualTo(CommandOutcome.Succeeded())
        assertThat(admin.calls).containsExactly("reboot")
    }

    @Test
    fun adminFailureBecomesFailedWithErrorCode() = runTest {
        deviceOwner()
        admin.rebootResult = { Outcome.Failure(TestError("reboot_denied")) }
        admin.installResult = { Outcome.Failure(TestError("hash_mismatch")) }
        admin.lockResult = { Outcome.Failure(TestError("not_whitelisted")) }

        assertThat(execute(Command.Reboot)).isEqualTo(CommandOutcome.Failed("reboot_denied"))
        assertThat(execute(Command.InstallApk("https://apk-host/a.apk", sha)))
            .isEqualTo(CommandOutcome.Failed("hash_mismatch"))
        assertThat(execute(Command.LockTask("ru.restofleet.kiosk", null)))
            .isEqualTo(CommandOutcome.Failed("not_whitelisted"))
    }

    @Test
    fun installAndLockTaskArePassedToAdmin() = runTest {
        deviceOwner()

        assertThat(execute(Command.InstallApk("https://apk-host/a.apk", sha))).isEqualTo(CommandOutcome.Succeeded())
        assertThat(execute(Command.LockTask("ru.restofleet.kiosk", "https://kitchen/s")))
            .isEqualTo(CommandOutcome.Succeeded())
        assertThat(admin.calls)
            .containsExactly("install:https://apk-host/a.apk:$sha", "lock:ru.restofleet.kiosk:https://kitchen/s")
            .inOrder()
    }

    @Test
    fun invalidCommandIsUnsupportedWithoutPortCalls() = runTest {
        deviceOwner()

        assertThat(execute(Command.InstallApk("http://x/a.apk", sha)))
            .isEqualTo(CommandOutcome.Unsupported("invalid_url"))
        assertThat(execute(Command.Beep(0))).isEqualTo(CommandOutcome.Unsupported("invalid_duration"))
        assertThat(execute(Command.LaunchApp("bad", null))).isEqualTo(CommandOutcome.Unsupported("invalid_package"))
        assertNoPortCalls()
    }

    @Test
    fun launchAppPassesStrategyToLauncher() = runTest {
        val launch = Command.LaunchApp("ru.restofleet.kiosk", "https://kitchen/s")

        caps = Capabilities(AgentMode.NORMAL, 34, setOf(Permission.POST_NOTIFICATIONS))
        assertThat(execute(launch)).isEqualTo(CommandOutcome.Succeeded())
        caps = Capabilities(AgentMode.NORMAL, 34, setOf(Permission.SYSTEM_ALERT_WINDOW))
        execute(launch)
        deviceOwner()
        execute(launch)

        assertThat(launcher.calls.map { it.strategy }).containsExactly(
            LaunchStrategy.VIA_NOTIFICATION,
            LaunchStrategy.DIRECT_OVERLAY,
            LaunchStrategy.DIRECT_DO,
        ).inOrder()
        assertThat(launcher.calls.first())
            .isEqualTo(
                FakeAppLauncher.Call("ru.restofleet.kiosk", "https://kitchen/s", LaunchStrategy.VIA_NOTIFICATION),
            )
    }

    @Test
    fun launchViaNotificationWithoutPermissionIsUnsupported() = runTest {
        caps = Capabilities(AgentMode.NORMAL, 34, emptySet())

        assertThat(execute(Command.LaunchApp("ru.restofleet.kiosk", null)))
            .isEqualTo(CommandOutcome.Unsupported("notifications_denied"))
        assertNoPortCalls()
    }

    @Test
    fun showMessageGoesToNotifierAndPortResultIsReturned() = runTest {
        notifier.result = { Outcome.Success(mapOf("shown" to "true")) }

        assertThat(execute(Command.ShowMessage("Перезагрузите роутер", Level.WARNING)))
            .isEqualTo(CommandOutcome.Succeeded(mapOf("shown" to "true")))
        assertThat(notifier.calls).containsExactly("Перезагрузите роутер" to Level.WARNING)
    }

    @Test
    fun beepGoesToBeeperAndPortResultIsReturned() = runTest {
        assertThat(execute(Command.Beep(3_000)))
            .isEqualTo(CommandOutcome.Succeeded(mapOf("ringerMode" to "normal")))
        assertThat(beeper.calls).containsExactly(3_000L)
    }

    @Test
    fun normalPortFailureBecomesFailedWithErrorCode() = runTest {
        notifier.result = { Outcome.Failure(TestError("channel_blocked")) }
        launcher.result = { Outcome.Failure(TestError("activity_not_found")) }
        beeper.result = { Outcome.Failure(TestError("muted")) }

        assertThat(execute(Command.ShowMessage("x", Level.INFO))).isEqualTo(CommandOutcome.Failed("channel_blocked"))
        assertThat(execute(Command.LaunchApp("ru.restofleet.kiosk", null)))
            .isEqualTo(CommandOutcome.Failed("activity_not_found"))
        assertThat(execute(Command.Beep(100))).isEqualTo(CommandOutcome.Failed("muted"))
    }

    @Test
    fun portExceptionBecomesFailedWithClassName() = runTest {
        deviceOwner()
        admin.rebootResult = { throw IllegalStateException("boom") }

        assertThat(execute(Command.Reboot)).isEqualTo(CommandOutcome.Failed("exception:IllegalStateException"))
    }

    @Test
    fun capabilitiesSourceExceptionBecomesFailed() = runTest {
        val broken = ExecuteCommand(
            { error("no caps") }, admin, notifier, launcher, beeper, Clock { 1L },
        )

        assertThat(broken(Command.Ping)).isEqualTo(CommandOutcome.Failed("exception:IllegalStateException"))
    }

    @Test
    fun strayCancellationFromPortIsFailure() = runTest {
        beeper.result = { throw CancellationException("stray") }

        assertThat(execute(Command.Beep(100))).isEqualTo(CommandOutcome.Failed("exception:CancellationException"))
    }

    @Test
    fun cancellationOfCallerPropagatesFromInvokeItself() = runTest {
        lateinit var outer: Job
        beeper.result = {
            outer.cancel()
            throw CancellationException("cancelled by caller") // без suspend-точки
        }
        var outcome: Result<CommandOutcome>? = null
        outer = launch { outcome = runCatching { execute(Command.Beep(100)) } }

        runCurrent()

        assertThat(outcome!!.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun errorIsNotSwallowed() = runTest {
        beeper.result = { throw OutOfMemoryError("oom") }

        assertFailsWith<OutOfMemoryError> { execute(Command.Beep(100)) }
    }
}
