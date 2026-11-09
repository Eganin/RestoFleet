package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import okhttp3.tls.HeldCertificate
import org.junit.Test
import ru.restofleet.shared.kernel.Outcome
import java.security.cert.CertificateException
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertThrows

class CaTrustTest {
    private val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("stand-ca").build()

    private fun trust(pem: ByteArray): X509TrustManager {
        val result = CaTrust.trustManagerFactory(pem)
        val tmf = (result as Outcome.Success).value
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    private fun leafSignedBy(issuer: HeldCertificate) =
        HeldCertificate.Builder().commonName("broker").addSubjectAlternativeName("10.0.2.2").signedBy(issuer).build()

    @Test
    fun validPemGivesFactory() {
        val result = CaTrust.trustManagerFactory(ca.certificatePem().toByteArray())

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
    }

    @Test
    fun leafSignedByThePinnedCaIsAccepted() {
        val tm = trust(ca.certificatePem().toByteArray())

        tm.checkServerTrusted(arrayOf(leafSignedBy(ca).certificate), "ECDHE_ECDSA")
    }

    @Test
    fun leafSignedByAnotherCaIsRejected() {
        val tm = trust(ca.certificatePem().toByteArray())
        val other = HeldCertificate.Builder().certificateAuthority(0).commonName("other").build()

        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(leafSignedBy(other).certificate), "ECDHE_ECDSA")
        }
    }

    @Test
    fun systemRootsAreNotTrusted() {
        val tm = trust(ca.certificatePem().toByteArray())

        assertThat(tm.acceptedIssuers.map { it.subjectX500Principal.name }).containsExactly("CN=stand-ca")
    }

    @Test
    fun emptyPemIsCaInvalid() {
        val result = CaTrust.trustManagerFactory(ByteArray(0))

        assertThat((result as Outcome.Failure).error.code).isEqualTo("ca_invalid")
    }

    @Test
    fun garbageIsCaInvalid() {
        val result = CaTrust.trustManagerFactory("not a certificate".toByteArray())

        assertThat((result as Outcome.Failure).error.code).isEqualTo("ca_invalid")
    }

    @Test
    fun privateKeyPemIsCaInvalid() {
        val result = CaTrust.trustManagerFactory(ca.privateKeyPkcs8Pem().toByteArray())

        assertThat((result as Outcome.Failure).error.code).isEqualTo("ca_invalid")
    }

    @Test
    fun certificateBundleWithTwoCaTrustsBoth() {
        val second = HeldCertificate.Builder().certificateAuthority(0).commonName("second").build()
        val tm = trust((ca.certificatePem() + second.certificatePem()).toByteArray())

        tm.checkServerTrusted(arrayOf(leafSignedBy(second).certificate), "ECDHE_ECDSA")
    }
}
