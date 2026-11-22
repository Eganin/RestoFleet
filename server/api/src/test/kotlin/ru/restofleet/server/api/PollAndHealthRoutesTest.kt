package ru.restofleet.server.api

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import ru.restofleet.server.domain.command.CommandReport
import ru.restofleet.server.domain.command.ReportedCommandStatus
import ru.restofleet.server.domain.common.ServerError
import ru.restofleet.server.domain.health.HealthReport
import ru.restofleet.server.domain.ingest.DeviceDataBatch
import ru.restofleet.server.domain.ingest.IngestOutcome
import ru.restofleet.server.domain.ingest.IngestResult
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.rest.PollCommandsResponseDto
import ru.restofleet.shared.shadow.ShadowDocument
import ru.restofleet.shared.shadow.ShadowValue

/** REST-polling (Basic, BOLA) и `/health`. */
class PollAndHealthRoutesTest {
    private val kit = ApiTestKit()
    private val id = Samples.deviceId
    private val other = DeviceId.parse("d-2")!!

    private fun authenticated() {
        coEvery { kit.poll.authenticate("d-1", "pw-1") } returns Outcome.Success(id)
        coEvery { kit.poll.authenticate("d-2", "pw-2") } returns Outcome.Success(other)
    }

    @Test
    fun `telemetry body is decoded for the authenticated device and answered with 204`() = kit.run {
        authenticated()
        val batch = DeviceDataBatch(telemetry = listOf(Samples.telemetry))
        every { kit.poll.decoder.telemetryBatch(id, "{body}") } returns Outcome.Success(batch)
        coEvery { kit.poll.telemetry(id, batch) } returns IngestResult(1, 0, 0, 0)

        val response = client.post("/api/v1/poll/telemetry") { basicAuth("d-1", "pw-1"); json("{body}") }

        assertThat(response.status).isEqualTo(HttpStatusCode.NoContent)
        assertThat(response.bodyAsText()).isEmpty()
        coVerify { kit.poll.telemetry(id, batch) }
    }

    @Test
    fun `rejected payload is 400 with the decoder reason and nothing is written`() = kit.run {
        authenticated()
        every { kit.poll.decoder.telemetryBatch(id, any()) } returns Outcome.Failure("bad_ts:telemetry")

        val response = client.post("/api/v1/poll/telemetry") { basicAuth("d-1", "pw-1"); json("{}") }

        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(response.errorCode()).isEqualTo("invalid_payload")
        assertThat(response.json()["reason"]!!.jsonPrimitive.content).isEqualTo("bad_ts:telemetry")
        coVerify(exactly = 0) { kit.poll.telemetry(any(), any()) }
    }

    @Test
    fun `each device gets only its own commands in the cmd req format`() = kit.run {
        authenticated()
        val params = ShadowDocument(mapOf("text" to ShadowValue.Str("Проверьте Wi-Fi")))
        coEvery { kit.poll.commands(id) } returns listOf(Samples.command.copy(type = "show_message", params = params))
        coEvery { kit.poll.commands(other) } returns emptyList()

        val mine = client.get("/api/v1/poll/commands") { basicAuth("d-1", "pw-1") }
        val theirs = client.get("/api/v1/poll/commands") { basicAuth("d-2", "pw-2") }

        val dto = RfJson.decodeFromString(PollCommandsResponseDto.serializer(), mine.bodyAsText())
        assertThat(dto.commands.single().cmdId).isEqualTo("c-1")
        assertThat(dto.commands.single().params["text"]!!.jsonPrimitive.content).isEqualTo("Проверьте Wi-Fi")
        assertThat(dto.commands.single().expiresAt).isEqualTo(310_000)
        assertThat(RfJson.decodeFromString(PollCommandsResponseDto.serializer(), theirs.bodyAsText()).commands)
            .isEmpty()
    }

    @Test
    fun `command result of a foreign or unknown command is 404, own and repeated ones 204`() = kit.run {
        authenticated()
        val report = CommandReport(id, "c-1", ReportedCommandStatus.SUCCEEDED, 1)
        every { kit.poll.decoder.commandReport(id, any()) } returns Outcome.Success(report)
        every { kit.poll.decoder.commandReport(other, any()) } returns Outcome.Success(report.copy(deviceId = other))
        coEvery { kit.poll.result(report) } returns IngestOutcome.APPLIED andThen IngestOutcome.IGNORED_STALE
        coEvery { kit.poll.result(report.copy(deviceId = other)) } returns IngestOutcome.DROPPED_UNKNOWN_DEVICE

        val own = client.post("/api/v1/poll/commands/result") { basicAuth("d-1", "pw-1"); json("{}") }
        val repeated = client.post("/api/v1/poll/commands/result") { basicAuth("d-1", "pw-1"); json("{}") }
        val foreign = client.post("/api/v1/poll/commands/result") { basicAuth("d-2", "pw-2"); json("{}") }

        assertThat(own.status).isEqualTo(HttpStatusCode.NoContent)
        assertThat(repeated.status).isEqualTo(HttpStatusCode.NoContent)
        assertThat(foreign.status).isEqualTo(HttpStatusCode.NotFound)
        verify { kit.poll.decoder.commandReport(other, any()) }
    }

    @Test
    fun `broken command result is 400`() = kit.run {
        authenticated()
        every { kit.poll.decoder.commandReport(id, any()) } returns Outcome.Failure("bad_json:cmd/res:x")

        val response = client.post("/api/v1/poll/commands/result") { basicAuth("d-1", "pw-1"); json("{") }

        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        coVerify(exactly = 0) { kit.poll.result(any()) }
    }

    @Test
    fun `revoked device credentials are 401`() = kit.run {
        coEvery { kit.poll.authenticate("d-1", "old") } returns Outcome.Failure(ServerError.Unauthorized)

        val response = client.post("/api/v1/poll/telemetry") { basicAuth("d-1", "old"); json("{}") }

        assertThat(response.status).isEqualTo(HttpStatusCode.Unauthorized)
        verify(exactly = 0) { kit.poll.decoder.telemetryBatch(any(), any()) }
    }

    @Test
    fun `health is public on both paths, 200 when healthy and 503 when degraded`() = kit.run {
        coEvery { kit.health() } returns HealthReport(mapOf("database" to true, "broker" to true)) andThen
            HealthReport(mapOf("database" to true, "broker" to false))

        val ok = client.get("/health")
        val degraded = client.get("/api/v1/health")

        assertThat(ok.status).isEqualTo(HttpStatusCode.OK)
        assertThat(ok.json()["status"]!!.jsonPrimitive.content).isEqualTo("ok")
        assertThat(degraded.status).isEqualTo(HttpStatusCode.ServiceUnavailable)
        assertThat(degraded.json()["components"]!!.jsonObject["broker"]!!.jsonPrimitive.content).isEqualTo("false")
    }
}
