package ru.restofleet.server.data.ingest

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.ConnectException
import java.sql.SQLException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException

class TransientErrorsTest {

    @Test
    fun `lost connection, pool exhaustion, restarts and serialization conflicts are transient`() {
        listOf(
            SQLTransientConnectionException("pool timeout"),
            SQLRecoverableException("socket closed"),
            ConnectException("refused"),
            SQLException("connection failure", "08006"),
            SQLException("serialization", "40001"),
            SQLException("deadlock", "40P01"),
            SQLException("too many connections", "53300"),
            SQLException("admin shutdown", "57P01"),
        ).forEach { assertThat(TransientErrors.isTransient(it)).isTrue() }
    }

    @Test
    fun `constraint violations, bad data and bugs are permanent`() {
        listOf(
            SQLException("unique", "23505"),
            SQLException("fk", "23503"),
            SQLException("bad json", "22P02"),
            SQLException("no state"),
            IllegalStateException("bug"),
        ).forEach { assertThat(TransientErrors.isTransient(it)).isFalse() }
    }

    @Test
    fun `cause chain is searched, but not endlessly`() {
        val wrapped = RuntimeException("exposed", IllegalStateException("tx", SQLException("down", "08001")))
        assertThat(TransientErrors.isTransient(wrapped)).isTrue()

        var deep: Throwable = SQLException("down", "08001")
        repeat(10) { deep = RuntimeException("wrap $it", deep) }
        assertThat(TransientErrors.isTransient(deep)).isFalse()
    }
}
