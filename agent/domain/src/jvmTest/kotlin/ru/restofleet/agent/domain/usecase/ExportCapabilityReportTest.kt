package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CapabilityReport
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.domain.port.CapabilityReportWriter
import ru.restofleet.agent.domain.port.DeviceInfoSource
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.UnexpectedError

class ExportCapabilityReportTest {

    private val clock = Clock { NOW }
    private val device = DeviceInfo(36, "Pixel 9a", true, AgentMode.DEVICE_OWNER, "0.1.0")
    private val run = RunCapabilityChecks(emptyList(), clock)

    @Test
    fun writerReceivesReportWithAllResultsAndPathIsReturned() = runTest {
        var captured: CapabilityReport? = null
        val writer = CapabilityReportWriter { report ->
            captured = report
            Outcome.Success("/sdcard/report.json")
        }

        val outcome = ExportCapabilityReport(run, { device }, writer, clock)()

        assertThat(outcome).isEqualTo(Outcome.Success("/sdcard/report.json"))
        val report = captured!!
        assertThat(report.generatedAt).isEqualTo(NOW)
        assertThat(report.device).isEqualTo(device)
        assertThat(report.results).hasSize(CheckId.entries.size)
        assertThat(report.results.map { it.status }.toSet()).containsExactly(CheckStatus.NOT_CHECKED)
    }

    @Test
    fun writerFailureIsPropagatedUnchanged() = runTest {
        val error: DomainError = UnexpectedError("disk full")
        val writer = CapabilityReportWriter { Outcome.Failure(error) }

        val outcome = ExportCapabilityReport(run, DeviceInfoSource { device }, writer, clock)()

        assertThat(outcome).isEqualTo(Outcome.Failure(error))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
