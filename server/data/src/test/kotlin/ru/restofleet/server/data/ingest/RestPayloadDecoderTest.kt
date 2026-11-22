package ru.restofleet.server.data.ingest

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.server.domain.command.ReportedCommandStatus
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.errorOrNull
import ru.restofleet.shared.kernel.getOrNull
import java.io.File

class RestPayloadDecoderTest {
    private val decoder = RestPayloadDecoder(DeviceMessageDecoder(maxPayloadBytes = 4_096))
    private val id = DeviceId.parse("d-rest-1")!!

    private fun fixture(name: String) = File(System.getProperty("fixturesDir"), "$name.json").readText()

    @Test
    fun `poll telemetry fixture becomes one batch of the authenticated device`() {
        val batch = decoder.telemetryBatch(id, fixture("rest-poll-telemetry-request")).getOrNull()!!

        assertThat(batch.telemetry).hasSize(1)
        with(batch.telemetry.single()) {
            assertThat(deviceId).isEqualTo(id)
            assertThat(ts).isEqualTo(1_793_865_630_000L)
            assertThat(seq).isEqualTo(42L)
            assertThat(batteryLevel).isEqualTo(71)
            assertThat(rssi).isEqualTo(-58)
            assertThat(payload).contains("\"seq\":42")
        }
        assertThat(batch.probes.single().results.single().target).isEqualTo("api")
        assertThat(batch.events.single().type).isEqualTo("threshold")
        assertThat(batch.deviceIds()).containsExactly(id)
    }

    @Test
    fun `probe and events are optional`() {
        val body = """{"telemetry":{"ts":1793865630000,"seq":1}}"""

        val batch = decoder.telemetryBatch(id, body).getOrNull()!!

        assertThat(batch.telemetry).hasSize(1)
        assertThat(batch.probes).isEmpty()
        assertThat(batch.events).isEmpty()
    }

    @Test
    fun `service debug events are skipped like in mqtt ingest`() {
        val body = """{"telemetry":{"ts":1793865630000,"seq":1},""" +
            """"events":[{"type":"debug_seq","ts":1793865630000,"seq":1,"batchId":"b-1"}]}"""

        val batch = decoder.telemetryBatch(id, body).getOrNull()!!

        assertThat(batch.events).isEmpty()
    }

    @Test
    fun `the same guards as mqtt ingest reject the whole request`() {
        val cases = mapOf(
            "not json" to "bad_json",
            "[1,2]" to "bad_json",
            """{"probe":{"ts":1,"results":[]}}""" to "bad_json",
            """{"telemetry":{"ts":4102444800001,"seq":1}}""" to "bad_ts:telemetry",
            """{"telemetry":{"ts":1793865630000,"seq":1},"events":[{"type":"nope","ts":1}]}""" to "bad_json",
            """{"telemetry":{"ts":1793865630000,"seq":1,"foregroundApp":"a\u0000b"}}""" to "nul_char",
        )

        cases.forEach { (body, reason) ->
            val error = decoder.telemetryBatch(id, body).errorOrNull()
            assertWithBody(body, error).startsWith(reason)
        }
    }

    @Test
    fun `command result fixture is a report of the authenticated device`() {
        val report = decoder.commandReport(id, fixture("rest-poll-command-result")).getOrNull()!!

        assertThat(report.deviceId).isEqualTo(id)
        assertThat(report.cmdId).isNotEmpty()
        assertThat(report.status).isEqualTo(ReportedCommandStatus.SUCCEEDED)
    }

    @Test
    fun `broken command result is rejected with a reason and no payload`() {
        val error = decoder.commandReport(id, """{"cmdId":"c-1","status":"maybe","ts":1}""").errorOrNull()!!

        assertThat(error).startsWith("bad_json")
        assertThat(error).doesNotContain("maybe")
        assertThat(decoder.commandReport(id, "")).isInstanceOf(Outcome.Failure::class.java)
    }

    private fun assertWithBody(body: String, error: String?) =
        com.google.common.truth.Truth.assertWithMessage(body).that(error)
}
