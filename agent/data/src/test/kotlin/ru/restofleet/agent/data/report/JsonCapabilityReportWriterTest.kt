package ru.restofleet.agent.data.report

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CapabilityReport
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.shared.kernel.Outcome
import java.io.File

class JsonCapabilityReportWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun report(mode: AgentMode = AgentMode.NORMAL, api: Int = 36) = CapabilityReport(
        device = DeviceInfo(api, "Pixel 9a", true, mode, "0.1.0-mvp"),
        generatedAt = 1_700_000_000_000L,
        results = CheckId.entries.mapIndexed { i, id ->
            CheckResult(
                id = id,
                status = CheckStatus.entries[i % CheckStatus.entries.size],
                raw = if (i == 0) mapOf("level" to "87", "plugged" to "AC") else emptyMap(),
                note = if (i == 1) "нужна геолокация" else null,
                checkedAt = 1_700_000_000_000L + i,
            )
        },
    )

    private fun TestScope.writer(dir: () -> File?) =
        JsonCapabilityReportWriter(dir, StandardTestDispatcher(testScheduler))

    private fun parse(file: File): JsonObject = Json.parseToJsonElement(file.readText()).jsonObject

    @Test
    fun writesAllResultsToFileNamedByApiAndMode() = runTest {
        val outcome = writer { folder.root }.write(report())

        val path = (outcome as Outcome.Success).value
        assertThat(path).isEqualTo(File(folder.root, "capability-report-api36-normal.json").path)
        val json = parse(File(path))
        assertThat(json.getValue("results").jsonArray).hasSize(CheckId.entries.size)
        assertThat(CheckId.entries).hasSize(43)
    }

    @Test
    fun deviceOwnerModeIsReflectedInFileName() = runTest {
        val outcome = writer { folder.root }.write(report(AgentMode.DEVICE_OWNER, api = 26))

        assertThat((outcome as Outcome.Success).value).endsWith("capability-report-api26-device_owner.json")
    }

    @Test
    fun jsonCarriesDeviceInfoTimestampsAndLowercaseStatuses() = runTest {
        val path = (writer { folder.root }.write(report()) as Outcome.Success).value
        val json = parse(File(path))

        val device = json.getValue("deviceInfo").jsonObject
        assertThat(device.getValue("apiLevel").jsonPrimitive.content).isEqualTo("36")
        assertThat(device.getValue("model").jsonPrimitive.content).isEqualTo("Pixel 9a")
        assertThat(device.getValue("isEmulator").jsonPrimitive.content).isEqualTo("true")
        assertThat(device.getValue("mode").jsonPrimitive.content).isEqualTo("normal")
        assertThat(device.getValue("agentVersion").jsonPrimitive.content).isEqualTo("0.1.0-mvp")
        assertThat(json.getValue("ts").jsonPrimitive.content).isEqualTo("1700000000000")

        val results: JsonArray = json.getValue("results").jsonArray
        val first = results[0].jsonObject
        assertThat(first.getValue("id").jsonPrimitive.content).isEqualTo("L1")
        assertThat(first.getValue("status").jsonPrimitive.content).isEqualTo("ok")
        assertThat(first.getValue("raw").jsonObject.getValue("level").jsonPrimitive.content).isEqualTo("87")
        assertThat(first.getValue("note")).isEqualTo(JsonNull)
        assertThat(first.getValue("ts").jsonPrimitive.content).isEqualTo("1700000000000")
        val second = results[1].jsonObject
        assertThat(second.getValue("status").jsonPrimitive.content).isEqualTo("limited")
        assertThat(second.getValue("note").jsonPrimitive.content).isEqualTo("нужна геолокация")
        val statuses = results.map { it.jsonObject.getValue("status").jsonPrimitive.content }.toSet()
        assertThat(statuses).containsExactly("ok", "limited", "unavailable", "not_applicable", "not_checked")
    }

    @Test
    fun overwritesPreviousReportAndLeavesNoTempFile() = runTest {
        val w = writer { folder.root }
        w.write(report())
        w.write(report().copy(generatedAt = 5L))

        assertThat(folder.root.list().orEmpty().toList()).containsExactly("capability-report-api36-normal.json")
        assertThat(parse(File(folder.root, "capability-report-api36-normal.json")).getValue("ts").jsonPrimitive.content)
            .isEqualTo("5")
    }

    @Test
    fun createsMissingDirectory() = runTest {
        val dir = File(folder.root, "nested/files")

        val outcome = writer { dir }.write(report())

        assertThat(outcome).isInstanceOf(Outcome.Success::class.java)
        assertThat(File(dir, "capability-report-api36-normal.json").exists()).isTrue()
    }

    @Test
    fun failsWhenExternalFilesDirIsUnavailable() = runTest {
        val outcome = writer { null }.write(report())

        assertThat((outcome as Outcome.Failure).error).isEqualTo(ReportWriteFailed("external files dir unavailable"))
    }

    @Test
    fun failsInsteadOfThrowingOnIoError() = runTest {
        val notADirectory = folder.newFile("occupied")

        val outcome = writer { notADirectory }.write(report())

        val error = (outcome as Outcome.Failure).error
        assertThat(error.code).isEqualTo("report_write_failed")
    }

    @Test
    fun targetNameOccupiedByDirectoryFailsAndLeavesNoTempFile() = runTest {
        val dir = folder.newFolder("reports")
        val target = File(dir, "capability-report-api36-normal.json")
        target.mkdirs()
        File(target, "child").writeText("x")

        val outcome = writer { dir }.write(report())

        assertThat(outcome).isInstanceOf(Outcome.Failure::class.java)
        assertThat(File(dir, "capability-report-api36-normal.json.tmp").exists()).isFalse()
    }
}
