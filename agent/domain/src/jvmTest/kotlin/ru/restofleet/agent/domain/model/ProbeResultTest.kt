package ru.restofleet.agent.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProbeResultTest {
    private fun result(ok: Boolean, error: ProbeError?) =
        ProbeResult("t", ok, null, error, 10, ProbePhases(null, null, null, null), 1L)

    @Test
    fun httpCodesAreNotConsidered() {
        val r = ProbeResult("t", false, 503, ProbeError.HTTP_5XX, 10, ProbePhases(null, null, null, null), 1L)
        assertThat(r.tcpReachable).isFalse()
    }

    @Test
    fun acceptedConnectionMeansTcpReachable() {
        assertThat(result(ok = true, error = null).tcpReachable).isTrue()
    }

    @Test
    fun refusedConnectionStillMeansTcpReachable() {
        assertThat(result(ok = false, error = ProbeError.CONN_REFUSED).tcpReachable).isTrue()
    }

    @Test
    fun otherFailuresMeanHostUnreachable() {
        val unreachable = ProbeError.entries - ProbeError.CONN_REFUSED
        unreachable.forEach { assertThat(result(ok = false, error = it).tcpReachable).isFalse() }
    }
}
