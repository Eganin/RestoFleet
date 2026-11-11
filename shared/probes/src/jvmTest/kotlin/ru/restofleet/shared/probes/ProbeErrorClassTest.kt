package ru.restofleet.shared.probes

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class ProbeErrorClassTest {

    @Test
    fun wireCodesMatchProtocol() {
        assertThat(ProbeErrorClass.entries.map { it.code }).containsExactly(
            "dns_fail", "timeout", "conn_refused", "tls_error", "http_4xx", "http_5xx", "no_network",
            "local_network_denied", "other",
        ).inOrder()
    }

    @Test
    fun fromCodeRoundTripsAndFallsBackToOther() {
        ProbeErrorClass.entries.forEach { assertThat(ProbeErrorClass.fromCode(it.code)).isEqualTo(it) }
        assertThat(ProbeErrorClass.fromCode(null)).isNull()
        assertThat(ProbeErrorClass.fromCode("quic_fail")).isEqualTo(ProbeErrorClass.OTHER)
    }

    @Test
    fun httpStatusWithoutExpectationTable() {
        val cases = mapOf(
            100 to null, 200 to null, 204 to null, 301 to null, 399 to null,
            400 to ProbeErrorClass.HTTP_4XX, 404 to ProbeErrorClass.HTTP_4XX, 499 to ProbeErrorClass.HTTP_4XX,
            500 to ProbeErrorClass.HTTP_5XX, 503 to ProbeErrorClass.HTTP_5XX, 599 to ProbeErrorClass.HTTP_5XX,
            600 to ProbeErrorClass.OTHER, 999 to ProbeErrorClass.OTHER,
        )
        cases.forEach { (code, expected) ->
            assertWithMessage("$code").that(classifyHttpStatus(code)).isEqualTo(expected)
        }
    }

    @Test
    fun httpStatusWithExpectationTable() {
        data class Case(val code: Int, val expected: Int, val result: ProbeErrorClass?)
        listOf(
            Case(204, 204, null),
            Case(200, 204, ProbeErrorClass.OTHER),
            Case(302, 200, ProbeErrorClass.OTHER),
            Case(401, 401, null),
            Case(401, 200, ProbeErrorClass.HTTP_4XX),
            Case(502, 200, ProbeErrorClass.HTTP_5XX),
            Case(700, 200, ProbeErrorClass.OTHER),
        ).forEach { case ->
            assertWithMessage("$case").that(classifyHttpStatus(case.code, case.expected)).isEqualTo(case.result)
        }
    }
}
