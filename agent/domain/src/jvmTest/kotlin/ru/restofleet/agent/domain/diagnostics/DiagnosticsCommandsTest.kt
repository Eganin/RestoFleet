package ru.restofleet.agent.domain.diagnostics

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.command.CommandOutcome
import ru.restofleet.agent.domain.fake.FakeBrokerSession
import ru.restofleet.agent.domain.fake.FakeMessagePublisher
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.fake.FakePayloadEncoder
import ru.restofleet.agent.domain.fake.FakeShadowStore
import ru.restofleet.agent.domain.fake.FakeTelemetrySource
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.agent.domain.model.ProbePhases
import ru.restofleet.agent.domain.model.ProbeResult
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason
import ru.restofleet.agent.domain.port.ProbeExecutor
import ru.restofleet.agent.domain.port.WatchedApps
import ru.restofleet.agent.domain.probe.ProbeStates
import ru.restofleet.agent.domain.usecase.CollectTelemetry
import ru.restofleet.agent.domain.usecase.PublishEvent
import ru.restofleet.agent.domain.usecase.PublishOrBuffer
import ru.restofleet.agent.domain.usecase.PublishProbeResult
import ru.restofleet.agent.domain.usecase.PublishTelemetry
import ru.restofleet.agent.domain.usecase.RunProbes
import ru.restofleet.shared.kernel.Clock

/** `run_probe{target}` и `collect_diagnostics{}` (FR-AG-62, 64, реш. 221): строковый `result`. */
class DiagnosticsCommandsTest {
    private val clock = Clock { 900 }
    private val publisher = FakeMessagePublisher()
    private val encoder = FakePayloadEncoder()
    private val publish = PublishOrBuffer(FakeBrokerSession(), publisher, FakeOutbox())
    private val shadow = FakeShadowStore()
    private val telemetry = FakeTelemetrySource()
    private val probed = mutableListOf<String>()
    private val watchedAsked = mutableListOf<List<String>>()
    private val executor = ProbeExecutor { t ->
        probed += t.id
        val ok = t.id != "kds"
        ProbeResult(
            targetId = t.id,
            ok = ok,
            httpStatus = if (ok) 204 else null,
            error = if (ok) null else ProbeError.DNS_FAIL,
            totalMs = 42,
            phases = ProbePhases(null, null, null, null),
            ts = 800,
        )
    }
    private val runProbes = RunProbes(
        executor,
        ProbeStates(),
        PublishProbeResult(publish, encoder, clock),
        PublishEvent(publish, encoder, clock),
    )
    private var seq = 0L
    private val sut = DiagnosticsCommands(
        shadow = shadow,
        runProbes = runProbes,
        collect = CollectTelemetry(telemetry, clock, { ++seq }),
        publishTelemetry = PublishTelemetry(publish, encoder, clock),
        watched = WatchedApps { setOf("com.android.chrome") },
    )

    private suspend fun shadowWith(vararg ids: String) {
        shadow.save(
            DesiredShadow(
                version = 3,
                telemetryIntervalSec = 30,
                watchedApps = emptyList(),
                probes = ids.map { ProbeTarget(it, "https://$it.example/", 1_000) },
            ),
        )
    }

    @Test
    fun runProbeRunsOnlyTheNamedTargetAndReportsStrings() = runTest {
        shadowWith("api", "kds")

        val outcome = sut.runProbe("api")

        assertThat(probed).containsExactly("api")
        assertThat(outcome).isEqualTo(
            CommandOutcome.Succeeded(
                mapOf("target" to "api", "ok" to "true", "status" to "204", "totalMs" to "42"),
            ),
        )
    }

    @Test
    fun failedProbeIsStillASucceededCommandWithError() = runTest {
        shadowWith("kds")

        val outcome = sut.runProbe("kds") as CommandOutcome.Succeeded

        assertThat(outcome.result).containsExactly("target", "kds", "ok", "false", "error", "dns_fail", "totalMs", "42")
    }

    @Test
    fun unknownTargetFailsWithoutProbing() = runTest {
        shadowWith("api")

        assertThat(sut.runProbe("nope")).isEqualTo(CommandOutcome.Failed("unknown_target"))
        assertThat(sut.runProbe("api ")).isEqualTo(CommandOutcome.Failed("unknown_target"))
        assertThat(probed).isEmpty()
    }

    @Test
    fun runProbeWithoutShadowFails() = runTest {
        assertThat(sut.runProbe("api")).isEqualTo(CommandOutcome.Failed("unknown_target"))
    }

    @Test
    fun collectDiagnosticsPublishesTelemetryAndRunsAllProbes() = runTest {
        shadowWith("api", "kds")
        telemetry.foregroundResult = { Reading.Unavailable(UnavailableReason.PermissionMissing("usage")) }
        telemetry.appsResult = { watched ->
            watchedAsked += watched
            Reading.Available(emptyList())
        }

        val outcome = sut.collectDiagnostics()

        assertThat(probed).containsExactly("api", "kds")
        assertThat(watchedAsked).containsExactly(listOf("com.android.chrome"))
        assertThat(publisher.published.map { it.kind }).contains(MessageKind.TELEMETRY)
        assertThat(publisher.published.map { it.kind }).contains(MessageKind.PROBE)
        assertThat(outcome).isEqualTo(
            CommandOutcome.Succeeded(
                mapOf(
                    "seq" to "1",
                    "probes" to "2",
                    "probesOk" to "1",
                    "probesFailed" to "kds",
                    "unavailable" to "foregroundApp",
                ),
            ),
        )
    }

    @Test
    fun collectDiagnosticsWithoutProbesStillSendsTelemetry() = runTest {
        val outcome = sut.collectDiagnostics() as CommandOutcome.Succeeded

        assertThat(publisher.published.map { it.kind }).containsExactly(MessageKind.TELEMETRY)
        assertThat(outcome.result).containsEntry("probes", "0")
        assertThat(outcome.result).containsEntry("probesFailed", "")
        assertThat(outcome.result).containsEntry("unavailable", "")
    }
}
