package ru.restofleet.agent.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProbeResultTest {
    private fun result(ok: Boolean, error: ProbeError?) =
        ProbeResult("t", ok, null, error, 10, ProbePhases(null, null, null, null), 1L)

    @Test
    fun acceptedConnectionMeansHostReachable() {
        assertThat(result(ok = true, error = null).hostReachable).isTrue()
    }

    @Test
    fun refusedConnectionStillMeansHostReachable() {
        assertThat(result(ok = false, error = ProbeError.CONN_REFUSED).hostReachable).isTrue()
    }

    @Test
    fun otherFailuresMeanHostUnreachable() {
        val unreachable = ProbeError.entries - ProbeError.CONN_REFUSED
        unreachable.forEach { assertThat(result(ok = false, error = it).hostReachable).isFalse() }
    }
}
