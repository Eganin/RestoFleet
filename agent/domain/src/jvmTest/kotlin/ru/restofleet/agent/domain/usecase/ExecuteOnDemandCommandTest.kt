package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.command.Command
import ru.restofleet.agent.domain.command.CommandOutcome
import ru.restofleet.agent.domain.diagnostics.OnDemandChecks
import ru.restofleet.agent.domain.fake.FakeAppLauncher
import ru.restofleet.agent.domain.fake.FakeBeeper
import ru.restofleet.agent.domain.fake.FakeDeviceAdmin
import ru.restofleet.agent.domain.fake.FakeUserNotifier
import ru.restofleet.agent.domain.port.CapabilitiesSource
import ru.restofleet.shared.kernel.Clock

/** `run_probe` и `collect_diagnostics` (FR-AG-62, 64, реш. 221) идут в [OnDemandChecks]. */
class ExecuteOnDemandCommandTest {
    private val calls = mutableListOf<String>()
    private val onDemand = object : OnDemandChecks {
        override suspend fun runProbe(targetId: String): CommandOutcome {
            calls += "probe:$targetId"
            return CommandOutcome.Succeeded(mapOf("target" to targetId))
        }

        override suspend fun collectDiagnostics(): CommandOutcome {
            calls += "diagnostics"
            return CommandOutcome.Succeeded(mapOf("probes" to "0"))
        }
    }

    private fun executor(checks: OnDemandChecks = onDemand, kiosk: Boolean = false) = ExecuteCommand(
        caps = CapabilitiesSource { Capabilities(AgentMode.NORMAL, 34, emptySet(), kioskActive = kiosk) },
        admin = FakeDeviceAdmin(),
        notifier = FakeUserNotifier(),
        launcher = FakeAppLauncher(),
        beeper = FakeBeeper(),
        clock = Clock { 1L },
        onDemand = checks,
    )

    @Test
    fun runProbeGoesToOnDemandChecks() = runTest {
        assertThat(executor()(Command.RunProbe("api"))).isEqualTo(CommandOutcome.Succeeded(mapOf("target" to "api")))
        assertThat(calls).containsExactly("probe:api")
    }

    @Test
    fun collectDiagnosticsGoesToOnDemandChecks() = runTest {
        assertThat(executor()(Command.CollectDiagnostics))
            .isEqualTo(CommandOutcome.Succeeded(mapOf("probes" to "0")))
        assertThat(calls).containsExactly("diagnostics")
    }

    @Test
    fun blankTargetIsUnsupportedWithoutRunning() = runTest {
        assertThat(executor()(Command.RunProbe(" "))).isEqualTo(CommandOutcome.Unsupported("invalid_target"))
        assertThat(calls).isEmpty()
    }

    @Test
    fun kioskDoesNotBlockDiagnostics() = runTest {
        val kiosk = executor(kiosk = true)

        assertThat(kiosk(Command.CollectDiagnostics)).isInstanceOf(CommandOutcome.Succeeded::class.java)
        assertThat(kiosk(Command.RunProbe("api"))).isInstanceOf(CommandOutcome.Succeeded::class.java)
    }

    @Test
    fun withoutOnDemandChecksCommandsAreUnsupported() = runTest {
        val bare = ExecuteCommand(
            CapabilitiesSource { Capabilities(AgentMode.NORMAL, 34, emptySet()) },
            FakeDeviceAdmin(), FakeUserNotifier(), FakeAppLauncher(), FakeBeeper(), Clock { 1L },
        )

        assertThat(bare(Command.RunProbe("api"))).isEqualTo(CommandOutcome.Unsupported("not_available"))
        assertThat(bare(Command.CollectDiagnostics)).isEqualTo(CommandOutcome.Unsupported("not_available"))
    }

    @Test
    fun onDemandExceptionBecomesFailed() = runTest {
        val broken = object : OnDemandChecks {
            override suspend fun runProbe(targetId: String): CommandOutcome = error("boom")

            override suspend fun collectDiagnostics(): CommandOutcome = error("boom")
        }

        assertThat(executor(broken)(Command.RunProbe("api")))
            .isEqualTo(CommandOutcome.Failed("exception:IllegalStateException"))
    }
}
