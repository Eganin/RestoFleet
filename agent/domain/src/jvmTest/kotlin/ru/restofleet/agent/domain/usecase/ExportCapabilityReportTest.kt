package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CapabilityReport
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.domain.fake.FakeCheckResultJournal
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

        val outcome = ExportCapabilityReport(run, { device }, writer, clock, FakeCheckResultJournal())()

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

        val export = ExportCapabilityReport(run, DeviceInfoSource { device }, writer, clock, FakeCheckResultJournal())
        val outcome = export()

        assertThat(outcome).isEqualTo(Outcome.Failure(error))
    }

    @Test
    fun manuallyRunChecksFromTheJournalReplaceNotCheckedOnes() = runTest {
        val journal = FakeCheckResultJournal()
        val c4 = CheckResult(CheckId.C4, CheckStatus.OK, mapOf("ringerMode" to "silent"), null, 5L)
        journal.record(c4)
        var captured: CapabilityReport? = null
        val writer = CapabilityReportWriter {
            captured = it
            Outcome.Success("/r.json")
        }

        ExportCapabilityReport(run, { device }, writer, clock, journal)()

        val results = captured!!.results
        assertThat(results).hasSize(CheckId.entries.size)
        assertThat(results.first { it.id == CheckId.C4 }).isEqualTo(c4)
        assertThat(results.first { it.id == CheckId.C1 }.status).isEqualTo(CheckStatus.NOT_CHECKED)
    }

    @Test
    fun journalNeverOverridesAResultThatTheRunProduced() = runTest {
        val produced = CheckResult(CheckId.R1, CheckStatus.OK, emptyMap(), null, 7L)
        val check = object : CapabilityCheck {
            override val id = CheckId.R1
            override suspend fun run() = produced
        }
        val journal = FakeCheckResultJournal()
        journal.record(CheckResult(CheckId.R1, CheckStatus.UNAVAILABLE, emptyMap(), "stale", 1L))
        var captured: CapabilityReport? = null
        val writer = CapabilityReportWriter {
            captured = it
            Outcome.Success("/r.json")
        }

        ExportCapabilityReport(RunCapabilityChecks(listOf(check), clock), { device }, writer, clock, journal)()

        assertThat(captured!!.results.first { it.id == CheckId.R1 }).isEqualTo(produced)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
