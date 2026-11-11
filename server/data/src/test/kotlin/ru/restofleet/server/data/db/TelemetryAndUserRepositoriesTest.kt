package ru.restofleet.server.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.Samples.T0
import ru.restofleet.server.data.support.TestDatabase
import ru.restofleet.server.domain.auth.RefreshToken
import ru.restofleet.server.domain.auth.User
import ru.restofleet.server.domain.common.PageRequest
import ru.restofleet.server.domain.common.TimeRange
import ru.restofleet.server.domain.ingest.DeviceEvent
import ru.restofleet.server.domain.ingest.ProbeRecord
import ru.restofleet.server.domain.ingest.ProbeReport
import ru.restofleet.server.domain.ingest.ProbeResult
import java.sql.SQLException
import kotlin.test.assertFailsWith

internal class TelemetryAndUserRepositoriesTest : PostgresTest() {
    private val telemetry = ExposedTelemetryRepository()
    private val users = ExposedUserRepository()
    private val refresh = ExposedRefreshTokenRepository()

    private fun probe(target: String, ok: Boolean = true) =
        ProbeResult(target, ok, if (ok) 200 else null, if (ok) null else "timeout", 120, 5, 10, 20, 30)

    @Test
    fun `telemetry insert ignores duplicates by device and ts and counts only new rows (decision 205)`() {
        val d = givenDevice()
        val other = givenDevice()
        val batch = listOf(
            Samples.telemetry(d.id, T0),
            Samples.telemetry(d.id, T0 + 30_000),
            Samples.telemetry(d.id, T0, seq = 1),
            Samples.telemetry(other.id, T0),
        )

        val first = tx { telemetry.insertTelemetry(batch) }
        val again = tx { telemetry.insertTelemetry(batch.take(2) + Samples.telemetry(d.id, T0 + 60_000)) }

        assertThat(first).isEqualTo(3)
        assertThat(again).isEqualTo(1)
        assertThat(TestDatabase.scalar("SELECT count(*) FROM telemetry")).isEqualTo("4")
        val firstSeq = "SELECT seq FROM telemetry WHERE device_id = '${d.id.value}' ORDER BY ts LIMIT 1"
        assertThat(TestDatabase.scalar(firstSeq))
            .isEqualTo((T0 / 1_000).toString())
    }

    @Test
    fun `latest and history read newest first within a half open range with paging`() {
        val d = givenDevice()
        val rows = (0..4).map { Samples.telemetry(d.id, T0 + it * 1_000L) }
        tx { telemetry.insertTelemetry(rows) }

        // jsonb хранит payload в нормализованном виде: сравниваем без него
        assertThat(tx { telemetry.latest(d.id) }!!.copy(payload = "")).isEqualTo(rows.last().copy(payload = ""))
        assertThat(tx { telemetry.latest(Samples.deviceId()) }).isNull()
        val range = TimeRange(from = T0 + 1_000, to = T0 + 4_000)
        assertThat(tx { telemetry.telemetry(d.id, range, PageRequest.of()) }.map { it.ts })
            .containsExactly(T0 + 3_000, T0 + 2_000, T0 + 1_000).inOrder()
        assertThat(tx { telemetry.telemetry(d.id, TimeRange(), PageRequest.of(limit = 2, offset = 1)) }.map { it.ts })
            .containsExactly(T0 + 3_000, T0 + 2_000).inOrder()
        assertThat(tx { telemetry.telemetry(d.id, TimeRange(from = T0 + 9_000, to = T0), PageRequest.of()) }).isEmpty()
    }

    @Test
    fun `nullable readings and the raw payload survive the round trip`() {
        val d = givenDevice()
        val bare = Samples.telemetry(d.id, T0, battery = null).copy(
            charging = null, tempC = null, thermal = null, rssi = null, freeMb = null, availMb = null,
            foregroundApp = null,
            payload = """{"ts":1,"seq":2,"nested":{"x":[1,2.5,"s"]}}""",
        )

        tx { telemetry.insertTelemetry(listOf(bare)) }

        val stored = tx { telemetry.latest(d.id) }!!
        assertThat(stored.copy(payload = "")).isEqualTo(bare.copy(payload = ""))
        assertThat(TestDatabase.scalar("SELECT payload->'nested'->'x'->>1 FROM telemetry")).isEqualTo("2.5")
    }

    @Test
    fun `probe results are stored per target and a repeated run is ignored`() {
        val d = givenDevice()
        val report = ProbeReport(d.id, T0, listOf(probe("api", ok = false), probe("kds")))

        val first = tx { telemetry.insertProbes(listOf(report)) }
        val again = tx { telemetry.insertProbes(listOf(report, ProbeReport(d.id, T0 + 30_000, listOf(probe("api"))))) }

        assertThat(first).isEqualTo(2)
        assertThat(again).isEqualTo(1)
        assertThat(tx { telemetry.probes(d.id, TimeRange(), "api", PageRequest.of()) })
            .containsExactly(
                ProbeRecord(d.id, T0 + 30_000, probe("api")),
                ProbeRecord(d.id, T0, probe("api", ok = false)),
            )
            .inOrder()
        val firstRun = tx { telemetry.probes(d.id, TimeRange(to = T0 + 1), null, PageRequest.of()) }
        assertThat(firstRun.map { it.result.target })
            .containsExactly("api", "kds").inOrder()
    }

    @Test
    fun `events are appended without dedup and filtered by type`() {
        val d = givenDevice()
        val down = DeviceEvent(d.id, T0, "probe_state", """{"type":"probe_state","ts":1}""")
        val boot = DeviceEvent(d.id, T0 + 1, "agent_lifecycle", """{"type":"agent_lifecycle","ts":2}""")

        assertThat(tx { telemetry.insertEvents(listOf(down, boot, down)) }).isEqualTo(3)

        assertThat(tx { telemetry.events(d.id, TimeRange(), null, PageRequest.of()) }.map { it.type })
            .containsExactly("agent_lifecycle", "probe_state", "probe_state").inOrder()
        assertThat(tx { telemetry.events(d.id, TimeRange(), "probe_state", PageRequest.of(limit = 1)) }).hasSize(1)
        assertThat(tx { telemetry.events(d.id, TimeRange(from = T0 + 1), null, PageRequest.of()) }.single().payload)
            .contains("agent_lifecycle")
    }

    @Test
    fun `time series rows reference a registered device`() {
        val orphan = Samples.telemetry(Samples.deviceId(), T0)
        assertFailsWith<SQLException> { tx { telemetry.insertTelemetry(listOf(orphan)) } }
    }

    @Test
    fun `users are found by login and id and the password hash can be replaced`() {
        val user = User(Samples.uuid(), "admin", "\$2a\$10\$old", User.ROLE_ADMIN, T0)
        tx { users.insert(user) }

        assertThat(tx { users.findByLogin("admin") }).isEqualTo(user)
        assertThat(tx { users.findById(user.id) }).isEqualTo(user)
        assertThat(tx { users.findById("not-a-uuid") }).isNull()
        tx { users.updatePassword(user.id, "\$2a\$10\$new") }
        assertThat(tx { users.findByLogin("admin") }!!.passwordHash).isEqualTo("\$2a\$10\$new")
        assertFailsWith<SQLException> { tx { users.insert(user.copy(id = Samples.uuid())) } }
    }

    @Test
    fun `refresh token is consumed once, not after expiry, and revokeAll stops the rest`() {
        val user = User(Samples.uuid(), "admin", "h", User.ROLE_ADMIN, T0).also { tx { users.insert(it) } }
        fun token(n: Int, expiresAt: Long = T0 + 1_000) =
            RefreshToken(Samples.uuid(), user.id, n.toString().padStart(64, 'f'), T0, expiresAt, null)
        val a = token(1)
        val b = token(2)
        val old = token(3, expiresAt = T0 + 5)
        tx { listOf(a, b, old).forEach { refresh.insert(it) } }

        assertThat(tx { refresh.consume(a.tokenHash, T0 + 10) }).isEqualTo(a.copy(revokedAt = T0 + 10))
        assertThat(tx { refresh.consume(a.tokenHash, T0 + 11) }).isNull()
        assertThat(tx { refresh.consume(old.tokenHash, T0 + 10) }).isNull()
        assertThat(tx { refresh.findByHash(a.tokenHash) }!!.revokedAt).isEqualTo(T0 + 10)

        assertThat(tx { refresh.revokeAll(user.id, T0 + 20) }).isEqualTo(2)
        assertThat(tx { refresh.consume(b.tokenHash, T0 + 21) }).isNull()
        assertThat(tx { refresh.revokeAll("not-a-uuid", T0) }).isEqualTo(0)
        assertThat(tx { refresh.findByHash("0".repeat(64)) }).isNull()
    }
}
