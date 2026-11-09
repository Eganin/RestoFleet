package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import okhttp3.tls.HeldCertificate
import org.junit.Test
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession

class SanHostnameVerifierTest {
    private fun session(cert: HeldCertificate?): SSLSession = mockk {
        if (cert == null) {
            every { peerCertificates } throws SSLPeerUnverifiedException("none")
        } else {
            every { peerCertificates } returns arrayOf(cert.certificate)
        }
    }

    private fun cert(vararg san: String, cn: String = "broker") =
        HeldCertificate.Builder().commonName(cn).apply { san.forEach { addSubjectAlternativeName(it) } }.build()

    @Test
    fun ipInSanMatches() {
        assertThat(SanHostnameVerifier.verify("10.0.2.2", session(cert("10.0.2.2", "localhost")))).isTrue()
    }

    @Test
    fun ipNotInSanIsRejected() {
        assertThat(SanHostnameVerifier.verify("10.0.2.2", session(cert("192.168.1.5", "localhost")))).isFalse()
    }

    @Test
    fun certificateWithoutSanIsRejectedEvenIfCommonNameMatches() {
        val held = HeldCertificate.Builder().commonName("10.0.2.2").build()

        assertThat(SanHostnameVerifier.verify("10.0.2.2", session(held))).isFalse()
    }

    @Test
    fun dnsNameMatchesCaseInsensitively() {
        assertThat(SanHostnameVerifier.verify("Mosquitto", session(cert("mosquitto")))).isTrue()
    }

    @Test
    fun wildcardCoversOneLabelOnly() {
        val wildcard = session(cert("*.stand.example"))

        assertThat(SanHostnameVerifier.verify("broker.stand.example", wildcard)).isTrue()
        assertThat(SanHostnameVerifier.verify("a.broker.stand.example", wildcard)).isFalse()
        assertThat(SanHostnameVerifier.verify("stand.example", wildcard)).isFalse()
    }

    @Test
    fun dnsNameDoesNotMatchAnIpLiteral() {
        assertThat(SanHostnameVerifier.verify("10.0.2.2", session(cert("localhost")))).isFalse()
    }

    @Test
    fun missingPeerCertificateIsRejected() {
        assertThat(SanHostnameVerifier.verify("10.0.2.2", session(null))).isFalse()
    }
}
