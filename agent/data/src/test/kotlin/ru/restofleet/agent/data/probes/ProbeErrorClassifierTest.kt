package ru.restofleet.agent.data.probes

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.model.ProbeError
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class ProbeErrorClassifierTest {
    private fun classify(t: Throwable, network: Boolean = true) = ProbeErrorClassifier.fromThrowable(t, network)

    @Test
    fun unknownHostIsDnsFail() {
        assertThat(classify(UnknownHostException("nonexistent.invalid"))).isEqualTo(ProbeError.DNS_FAIL)
    }

    @Test
    fun timeoutsAreTimeout() {
        assertThat(classify(SocketTimeoutException("connect timed out"))).isEqualTo(ProbeError.TIMEOUT)
        assertThat(classify(InterruptedIOException("timeout"))).isEqualTo(ProbeError.TIMEOUT)
    }

    @Test
    fun otherInterruptedIoIsNotTimeout() {
        assertThat(classify(InterruptedIOException("thread interrupted"))).isEqualTo(ProbeError.OTHER)
        assertThat(classify(InterruptedIOException())).isEqualTo(ProbeError.OTHER)
    }

    @Test
    fun connectExceptionIsConnRefused() {
        assertThat(classify(ConnectException("Connection refused"))).isEqualTo(ProbeError.CONN_REFUSED)
    }

    @Test
    fun tlsFailuresAreTlsError() {
        assertThat(classify(SSLHandshakeException("PKIX path building failed"))).isEqualTo(ProbeError.TLS_ERROR)
        assertThat(classify(SSLPeerUnverifiedException("Hostname mismatch"))).isEqualTo(ProbeError.TLS_ERROR)
        assertThat(classify(CertificateException("expired"))).isEqualTo(ProbeError.TLS_ERROR)
    }

    @Test
    fun anyFailureWithoutNetworkIsNoNetwork() {
        val failures = listOf(
            UnknownHostException("x"),
            SocketTimeoutException("x"),
            ConnectException("x"),
            SSLHandshakeException("x"),
            SocketException("EPERM (Operation not permitted)"),
            IOException("x"),
        )
        failures.forEach { assertThat(classify(it, network = false)).isEqualTo(ProbeError.NO_NETWORK) }
    }

    @Test
    fun epermAndSecurityExceptionAreLocalNetworkDenied() {
        assertThat(classify(SocketException("EPERM (Operation not permitted)")))
            .isEqualTo(ProbeError.LOCAL_NETWORK_DENIED)
        assertThat(classify(SecurityException("denied"))).isEqualTo(ProbeError.LOCAL_NETWORK_DENIED)
    }

    @Test
    fun epermInsideConnectExceptionBeatsConnRefused() {
        val e = ConnectException(
            "failed to connect to /192.168.1.1 (port 80): connect failed: EPERM (Operation not permitted)",
        )
        assertThat(classify(e)).isEqualTo(ProbeError.LOCAL_NETWORK_DENIED)
    }

    private fun android(errno: String, text: String) = ConnectException(
        "failed to connect to /192.168.1.1 (port 80) from /192.168.1.50 (port 41234) after 3000ms: " +
            "isConnected failed: $errno ($text)",
    )

    @Test
    fun androidConnectExceptionIsClassifiedByErrno() {
        assertThat(classify(android("ECONNREFUSED", "Connection refused"))).isEqualTo(ProbeError.CONN_REFUSED)
        assertThat(classify(android("ETIMEDOUT", "Connection timed out"))).isEqualTo(ProbeError.TIMEOUT)
        assertThat(classify(android("ENETUNREACH", "Network is unreachable"))).isEqualTo(ProbeError.NO_NETWORK)
        assertThat(classify(android("EHOSTUNREACH", "No route to host"))).isEqualTo(ProbeError.OTHER)
        assertThat(classify(android("EPERM", "Operation not permitted"))).isEqualTo(ProbeError.LOCAL_NETWORK_DENIED)
        assertThat(classify(android("EACCES", "Permission denied"))).isEqualTo(ProbeError.LOCAL_NETWORK_DENIED)
    }

    @Test
    fun jvmMessagesAreClassified() {
        assertThat(classify(ConnectException("Connection refused"))).isEqualTo(ProbeError.CONN_REFUSED)
        assertThat(classify(ConnectException("Connection refused (Connection refused)")))
            .isEqualTo(ProbeError.CONN_REFUSED)
        assertThat(classify(ConnectException("Connection timed out (Connection timed out)")))
            .isEqualTo(ProbeError.TIMEOUT)
        assertThat(classify(SocketException("Network is unreachable (connect failed)")))
            .isEqualTo(ProbeError.NO_NETWORK)
        assertThat(classify(NoRouteToHostException("No route to host"))).isEqualTo(ProbeError.OTHER)
        assertThat(classify(SocketException("Permission denied"))).isEqualTo(ProbeError.LOCAL_NETWORK_DENIED)
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertThat(classify(ConnectException("CONNECTION REFUSED"))).isEqualTo(ProbeError.CONN_REFUSED)
        assertThat(classify(ConnectException("econnrefused"))).isEqualTo(ProbeError.CONN_REFUSED)
    }

    @Test
    fun unrecognizedConnectExceptionIsOther() {
        assertThat(classify(ConnectException("weird"))).isEqualTo(ProbeError.OTHER)
        assertThat(classify(ConnectException())).isEqualTo(ProbeError.OTHER)
    }

    @Test
    fun causeChainIsInspected() {
        val wrapped = IOException("call failed", android("ECONNREFUSED", "Connection refused"))
        assertThat(classify(wrapped)).isEqualTo(ProbeError.CONN_REFUSED)
        val eperm = ConnectException("connect failed")
            .apply { initCause(SocketException("EPERM (Operation not permitted)")) }
        assertThat(classify(eperm)).isEqualTo(ProbeError.LOCAL_NETWORK_DENIED)
    }

    @Test
    fun causeCycleDoesNotHang() {
        val a = ConnectException("a")
        val b = IOException("b", a)
        a.initCause(b)
        assertThat(classify(a)).isEqualTo(ProbeError.OTHER)
    }

    @Test
    fun plainSocketExceptionAndUnknownAreOther() {
        assertThat(classify(SocketException("Connection reset"))).isEqualTo(ProbeError.OTHER)
        assertThat(classify(SocketException())).isEqualTo(ProbeError.OTHER)
        assertThat(classify(IOException("boom"))).isEqualTo(ProbeError.OTHER)
        assertThat(classify(IllegalStateException("boom"))).isEqualTo(ProbeError.OTHER)
    }

    @Test
    fun statusClasses() {
        assertThat(ProbeErrorClassifier.fromStatus(200) as Any?).isNull()
        assertThat(ProbeErrorClassifier.fromStatus(204) as Any?).isNull()
        assertThat(ProbeErrorClassifier.fromStatus(399) as Any?).isNull()
        assertThat(ProbeErrorClassifier.fromStatus(400)).isEqualTo(ProbeError.HTTP_4XX)
        assertThat(ProbeErrorClassifier.fromStatus(404)).isEqualTo(ProbeError.HTTP_4XX)
        assertThat(ProbeErrorClassifier.fromStatus(499)).isEqualTo(ProbeError.HTTP_4XX)
        assertThat(ProbeErrorClassifier.fromStatus(500)).isEqualTo(ProbeError.HTTP_5XX)
        assertThat(ProbeErrorClassifier.fromStatus(503)).isEqualTo(ProbeError.HTTP_5XX)
        assertThat(ProbeErrorClassifier.fromStatus(599)).isEqualTo(ProbeError.HTTP_5XX)
        assertThat(ProbeErrorClassifier.fromStatus(600)).isEqualTo(ProbeError.OTHER)
    }
}
