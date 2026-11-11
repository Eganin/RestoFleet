package ru.restofleet.server.data.db

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.restofleet.server.data.support.Samples
import ru.restofleet.server.data.support.Samples.T0
import ru.restofleet.server.data.support.TestDatabase
import ru.restofleet.server.domain.common.inTransaction
import ru.restofleet.server.domain.presence.DeviceSession
import java.sql.SQLException
import kotlin.test.assertFailsWith

internal class ProvisioningAndSessionRepositoriesTest : PostgresTest() {
    private val tokens = ExposedTokenRepository()
    private val sessions = ExposedSessionRepository()

    private fun hash(n: Int) = n.toString().padStart(64, 'a')

    @Test
    fun `tokens are inserted in one batch and found by hash`() {
        val r = givenRestaurant()
        val batch = (1..3).map { Samples.token(r, hash(it)) }

        tx { tokens.insertAll(batch) }

        batch.forEach { assertThat(tx { tokens.findByHash(it.tokenHash) }).isEqualTo(it) }
        assertThat(tx { tokens.findByHash(hash(9)) }).isNull()
        assertFailsWith<SQLException> { tx { tokens.insertAll(listOf(Samples.token(r, hash(1)))) } }
    }

    @Test
    fun `redeem succeeds once, never for an expired code, and stamps usedAt`() {
        val r = givenRestaurant()
        val live = Samples.token(r, hash(1), expiresAt = T0 + 1_000)
        tx { tokens.insertAll(listOf(live, Samples.token(r, hash(2), expiresAt = T0 + 1_000))) }

        val first = tx { tokens.redeem(hash(1), T0 + 10) }
        val second = tx { tokens.redeem(hash(1), T0 + 20) }
        val expired = tx { tokens.redeem(hash(2), T0 + 1_000) }

        assertThat(first).isEqualTo(live.copy(usedAt = T0 + 10))
        assertThat(second).isNull()
        assertThat(expired).isNull()
        assertThat(tx { tokens.findByHash(hash(2)) }!!.usedAt).isNull()
        assertThat(tx { tokens.redeem(hash(3), T0) }).isNull()
    }

    @Test
    fun `two concurrent redeems of one code produce exactly one winner`() {
        val r = givenRestaurant()
        tx { tokens.insertAll(listOf(Samples.token(r, hash(7)))) }
        val bothInside = CompletableDeferred<Unit>()
        var inside = 0

        val results = runBlocking(Dispatchers.IO) {
            List(2) {
                async {
                    TestDatabase.tx.inTransaction {
                        // Обе транзакции начаты до погашения: гонку решает сам UPDATE … WHERE used_at IS NULL
                        synchronized(bothInside) { if (++inside == 2) bothInside.complete(Unit) }
                        bothInside.await()
                        tokens.redeem(hash(7), T0 + 1)
                    }
                }
            }.awaitAll()
        }

        assertThat(results.count { it != null }).isEqualTo(1)
    }

    @Test
    fun `attachDevice links the redeemed code to the new device`() {
        val r = givenRestaurant()
        val token = Samples.token(r, hash(4))
        val d = givenDevice(r)
        tx { tokens.insertAll(listOf(token)) }

        tx { tokens.attachDevice(token.id, d.id) }

        assertThat(tx { tokens.findByHash(hash(4)) }!!.deviceId).isEqualTo(d.id)
    }

    @Test
    fun `session open is idempotent per connId and latest is by connection time`() {
        val d = givenDevice()
        val first = DeviceSession(d.id, "c1", T0, null, null)
        val second = DeviceSession(d.id, "c2", T0 + 10, null, null)

        tx {
            sessions.open(first)
            sessions.open(first.copy(connectedAt = T0 + 99))
            sessions.open(second)
        }

        assertThat(tx { sessions.latest(d.id) }).isEqualTo(second)
        assertThat(TestDatabase.scalar("SELECT count(*) FROM device_sessions")).isEqualTo("2")
        assertThat(tx { sessions.latest(Samples.deviceId()) }).isNull()
    }

    @Test
    fun `close touches only the open session with that connId, closeOpen keeps the excepted one`() {
        val d = givenDevice()
        tx {
            sessions.open(DeviceSession(d.id, "c1", T0, null, null))
            sessions.open(DeviceSession(d.id, "c2", T0 + 1, null, null))
            sessions.open(DeviceSession(d.id, "c3", T0 + 2, null, null))
        }

        tx {
            sessions.close(d.id, "c1", T0 + 5, "lwt")
            sessions.close(d.id, "c1", T0 + 6, "stop")
            sessions.closeOpen(d.id, T0 + 7, "replaced", exceptConnId = "c3")
        }

        assertThat(TestDatabase.scalar("SELECT reason FROM device_sessions WHERE conn_id = 'c1'")).isEqualTo("lwt")
        assertThat(TestDatabase.scalar("SELECT reason FROM device_sessions WHERE conn_id = 'c2'")).isEqualTo("replaced")
        assertThat(tx { sessions.latest(d.id) }!!.isOpen).isTrue()

        tx { sessions.closeOpen(d.id, T0 + 8, "decommissioned") }
        assertThat(tx { sessions.latest(d.id) }).isEqualTo(DeviceSession(d.id, "c3", T0 + 2, T0 + 8, "decommissioned"))
    }

    @Test
    fun `overlong connId and reason are cut consistently for open and close`() {
        val d = givenDevice()
        val longId = "x".repeat(100)

        tx {
            sessions.open(DeviceSession(d.id, longId, T0, null, null))
            sessions.close(d.id, longId, T0 + 1, "r".repeat(50))
        }

        val stored = tx { sessions.latest(d.id) }!!
        assertThat(stored.connId).hasLength(64)
        assertThat(stored.reason).hasLength(32)
        assertThat(stored.isOpen).isFalse()
    }
}
