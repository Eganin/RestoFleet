package ru.restofleet.server.data.ingest

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.server.domain.command.ReportedCommandStatus
import ru.restofleet.server.domain.device.DeviceInfo
import ru.restofleet.server.domain.presence.PresenceState
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.protocol.DeviceChannel
import ru.restofleet.shared.protocol.Topics
import ru.restofleet.shared.shadow.ShadowKeys
import ru.restofleet.shared.shadow.ShadowValue
import java.io.File

class DeviceMessageDecoderTest {
    private val decoder = DeviceMessageDecoder(maxPayloadBytes = 4_096)
    private val id = DeviceId.parse("d-1")!!

    private fun fixture(name: String) = File(System.getProperty("fixturesDir"), "$name.json").readBytes()

    private fun decode(channel: DeviceChannel, payload: ByteArray) = decoder.decode(Topics.of(id, channel), payload)

    private fun decode(channel: DeviceChannel, text: String) = decode(channel, text.toByteArray())

    @Test
    fun `status online carries connId and what the device reports about itself`() {
        val decoded = decode(DeviceChannel.STATUS, fixture("status-online")) as Decoded.Status

        with(decoded.report) {
            assertThat(deviceId).isEqualTo(id)
            assertThat(state).isEqualTo(PresenceState.ONLINE)
            assertThat(connId).isEqualTo("k-7f3a9c")
            assertThat(announcement.agentVersion).isEqualTo("0.1.0-mvp")
            assertThat(announcement.mode).isEqualTo("normal")
            assertThat(announcement.info).isEqualTo(DeviceInfo("sdk_gphone64_arm64", "Google", "16", 36))
        }
    }

    @Test
    fun `lwt is offline with its reason`() {
        val decoded = decode(DeviceChannel.STATUS, fixture("status-offline-lwt")) as Decoded.Status

        assertThat(decoded.report.state).isEqualTo(PresenceState.OFFLINE)
        assertThat(decoded.report.reason).isEqualTo("lwt")
        assertThat(decoded.report.announcement.info).isNull()
    }

    @Test
    fun `telemetry fills every column and keeps the raw payload`() {
        val raw = fixture("telemetry-kitchen")

        val snapshot = (decode(DeviceChannel.TELEMETRY, raw) as Decoded.Telemetry).snapshot

        with(snapshot) {
            assertThat(ts).isEqualTo(1_793_865_630_000L)
            assertThat(seq).isEqualTo(18_234L)
            assertThat(batteryLevel).isEqualTo(87)
            assertThat(charging).isTrue()
            assertThat(tempC).isEqualTo(38.4)
            assertThat(thermal).isEqualTo("LIGHT")
            assertThat(rssi).isEqualTo(-67)
            assertThat(freeMb).isEqualTo(5_120L)
            assertThat(availMb).isEqualTo(1_430L)
            assertThat(foregroundApp).isEqualTo("com.android.chrome")
            assertThat(payload).isEqualTo(raw.decodeToString())
        }
    }

    @Test
    fun `missing readings stay null`() {
        val decoded = decode(DeviceChannel.TELEMETRY, fixture("telemetry-api26-minimal"))
        val snapshot = (decoded as Decoded.Telemetry).snapshot

        assertThat(snapshot.thermal).isNull()
        assertThat(snapshot.rssi).isNull()
        assertThat(snapshot.foregroundApp).isNull()
        assertThat(snapshot.batteryLevel).isEqualTo(54)
    }

    @Test
    fun `battery level outside 0-100 is dropped instead of overflowing smallint`() {
        val text = """{"ts":1,"seq":1,"battery":{"level":40000,"charging":false}}"""

        val snapshot = (decode(DeviceChannel.TELEMETRY, text) as Decoded.Telemetry).snapshot

        assertThat(snapshot.batteryLevel).isNull()
        assertThat(snapshot.charging).isFalse()
    }

    @Test
    fun `timestamps outside 1970-2100 are undecodable instead of failing the batch in postgres`() {
        listOf(
            DeviceChannel.TELEMETRY to """{"ts":-1,"seq":1}""",
            DeviceChannel.TELEMETRY to """{"ts":9223372036854775807,"seq":1}""",
            DeviceChannel.PROBE to """{"ts":-5,"results":[]}""",
            DeviceChannel.EVENTS to """{"type":"agent_lifecycle","ts":99999999999999999,"phase":"start"}""",
            DeviceChannel.SHADOW_REPORTED to """{"appliedVersion":1,"appliedAt":-1}""",
        ).forEach { (channel, text) ->
            val decoded = decode(channel, text)
            assertThat(decoded).isInstanceOf(Decoded.Undecodable::class.java)
            assertThat((decoded as Decoded.Undecodable).reason).isEqualTo("bad_ts:${channel.suffix}")
        }
    }

    @Test
    fun `nul characters are undecodable because postgres rejects them in text and jsonb`() {
        val escaped = """{"ts":1,"seq":1,"foregroundApp":"a\u0000b"}"""
        val raw = "{\"ts\":1,\"seq\":1,\"foregroundApp\":\"a\u0000b\"}"

        listOf(escaped, raw).forEach {
            val decoded = decode(DeviceChannel.TELEMETRY, it) as Decoded.Undecodable
            assertThat(decoded.reason).isEqualTo("nul_char")
        }
    }

    @Test
    fun `long strings are cut to the column sizes`() {
        val text = """{"state":"online","ts":1,"connId":"${"c".repeat(100)}","reason":"${"r".repeat(50)}"}"""

        val report = (decode(DeviceChannel.STATUS, text) as Decoded.Status).report

        assertThat(report.connId).hasLength(64)
        assertThat(report.reason).hasLength(32)
    }

    @Test
    fun `probe report normalizes error classes and drops duplicate targets`() {
        val text = """{"ts":5,"results":[
            {"id":"api","ok":false,"error":"weird_new_class","totalMs":10},
            {"id":"api","ok":true,"totalMs":11},
            {"id":"kds","ok":false,"error":"timeout","totalMs":12}]}"""

        val report = (decode(DeviceChannel.PROBE, text) as Decoded.Probe).report

        assertThat(report.results.map { it.target }).containsExactly("api", "kds").inOrder()
        assertThat(report.results.map { it.error }).containsExactly("other", "timeout").inOrder()
    }

    @Test
    fun `probe fixture keeps phase timings`() {
        val report = (decode(DeviceChannel.PROBE, fixture("probe-report")) as Decoded.Probe).report

        val kds = report.results.single { it.target == "kds" }
        assertThat(kds.status).isEqualTo(200)
        assertThat(listOf(kds.dnsMs, kds.connectMs, kds.tlsMs, kds.ttfbMs))
            .containsExactly(9L, 31L, 64L, 108L).inOrder()
        assertThat(report.results.single { it.target == "api" }.error).isEqualTo("timeout")
    }

    @Test
    fun `every event fixture becomes an event with its type, except debug_seq`() {
        val events = File(System.getProperty("fixturesDir")).listFiles { f -> f.name.startsWith("event-") }!!
        assertThat(events.size).isAtLeast(9)

        events.forEach { file ->
            val decoded = decode(DeviceChannel.EVENTS, file.readBytes())
            if (file.name == "event-debug-seq.json") {
                assertThat(decoded).isEqualTo(Decoded.Ignored("debug_seq"))
            } else {
                val event = (decoded as Decoded.Event).event
                assertThat(file.name).startsWith("event-${event.type.replace('_', '-')}")
                assertThat(event.payload).isEqualTo(file.readText())
            }
        }
    }

    @Test
    fun `unknown event type is undecodable`() {
        val decoded = decode(DeviceChannel.EVENTS, """{"type":"teleport","ts":1}""") as Decoded.Undecodable

        assertThat(decoded.reason).startsWith("bad_json:events")
    }

    @Test
    fun `reported separates metadata from the document`() {
        val decoded = decode(DeviceChannel.SHADOW_REPORTED, fixture("shadow-reported-errors"))
        val report = (decoded as Decoded.Reported).report

        assertThat(report.appliedVersion).isEqualTo(13)
        assertThat(report.appliedAt).isEqualTo(1_793_866_005_000L)
        assertThat(report.errors).containsExactly(
            "telemetryIntervalSec:out_of_range", "probes:duplicate_id:api", "watchedApps:invalid_name:Chrome",
        ).inOrder()
        assertThat(report.document.fields.keys).containsNoneIn(ShadowKeys.METADATA)
        assertThat(report.document["telemetry"]).isEqualTo(ShadowValue.Obj(mapOf("intervalSec" to ShadowValue.Num(30))))
    }

    @Test
    fun `reported without applied marks or not an object is undecodable`() {
        listOf("""{"telemetry":{}}""", """{"appliedVersion":"x","appliedAt":1}""", "[1]", "\"x\"").forEach {
            assertThat(decode(DeviceChannel.SHADOW_REPORTED, it)).isInstanceOf(Decoded.Undecodable::class.java)
        }
    }

    @Test
    fun `command results map every status and stringify result values`() {
        val expected = mapOf(
            "cmd-res-received" to ReportedCommandStatus.RECEIVED,
            "cmd-res-succeeded" to ReportedCommandStatus.SUCCEEDED,
            "cmd-res-failed-expired" to ReportedCommandStatus.FAILED,
            "cmd-res-unsupported" to ReportedCommandStatus.UNSUPPORTED,
        )
        expected.forEach { (name, status) ->
            val report = (decode(DeviceChannel.CMD_RES, fixture(name)) as Decoded.CommandResult).report
            assertThat(report.status).isEqualTo(status)
            assertThat(report.deviceId).isEqualTo(id)
        }
        val succeeded = (decode(DeviceChannel.CMD_RES, fixture("cmd-res-succeeded")) as Decoded.CommandResult).report
        assertThat(succeeded.result).containsExactly("deviceTime", "1793865802000")
        val mixed = """{"cmdId":"c-1","status":"succeeded","ts":1,"result":{"n":5,"o":{"a":1},"s":"x"}}"""
        val report = (decode(DeviceChannel.CMD_RES, mixed) as Decoded.CommandResult).report
        assertThat(report.result).containsExactly("n", "5", "o", """{"a":1}""", "s", "x")
        val failed = (decode(DeviceChannel.CMD_RES, fixture("cmd-res-failed-expired")) as Decoded.CommandResult).report
        assertThat(failed.reason).isEqualTo("expired")
    }

    @Test
    fun `foreign, malformed and to-device topics are not processed`() {
        assertThat(decoder.decode("rf/v1/dev/d-1/telemetry/extra", "{}".toByteArray()))
            .isEqualTo(Decoded.Undecodable("unknown_topic"))
        assertThat(decoder.decode("other/topic", "{}".toByteArray())).isEqualTo(Decoded.Undecodable("unknown_topic"))
        assertThat(decode(DeviceChannel.SHADOW_DESIRED, "{}")).isEqualTo(Decoded.Ignored("to_device"))
        assertThat(decode(DeviceChannel.CMD_REQ, "{}")).isEqualTo(Decoded.Ignored("to_device"))
    }

    @Test
    fun `empty payload is ignored and oversized one is undecodable before parsing`() {
        assertThat(decode(DeviceChannel.STATUS, ByteArray(0))).isEqualTo(Decoded.Ignored("empty"))
        assertThat(decode(DeviceChannel.TELEMETRY, ByteArray(4_097) { '{'.code.toByte() }))
            .isEqualTo(Decoded.Undecodable("too_large"))
    }

    @Test
    fun `broken json names the channel and the error but not the payload`() {
        val decoded = decode(DeviceChannel.TELEMETRY, """{"ts":1,"seq":"secret-value"}""") as Decoded.Undecodable

        assertThat(decoded.reason).startsWith("bad_json:telemetry:")
        assertThat(decoded.reason).doesNotContain("secret-value")
        val status = decode(DeviceChannel.STATUS, "{not json") as Decoded.Undecodable
        assertThat(status.reason).startsWith("bad_json:status:")
    }
}
