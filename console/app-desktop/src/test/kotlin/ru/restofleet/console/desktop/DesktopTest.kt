package ru.restofleet.console.desktop

import com.arkivanov.decompose.DecomposeSettings
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.resume
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import okhttp3.tls.HeldCertificate
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koin.dsl.koinApplication
import ru.restofleet.console.root.di.consoleModules
import ru.restofleet.console.root.di.createRootComponent
import java.io.File
import java.security.cert.CertificateException

class DesktopTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun pem(cert: HeldCertificate, name: String): File =
        tmp.newFile(name).also { it.writeText(cert.certificatePem()) }

    @Test
    fun `stand CA is trusted and others are not`() {
        val stand = HeldCertificate.Builder().commonName("stand").certificateAuthority(0).build()
        val other = HeldCertificate.Builder().commonName("other").certificateAuthority(0).build()
        val trust = StandTrust.trustManager(pem(stand, "ca.pem"))

        trust.checkServerTrusted(arrayOf(stand.certificate), "RSA")
        val rejected = runCatching { trust.checkServerTrusted(arrayOf(other.certificate), "RSA") }

        assertThat(rejected.exceptionOrNull()).isInstanceOf(CertificateException::class.java)
        assertThat(trust.acceptedIssuers.map { it.subjectX500Principal.name }).contains("CN=stand")
        assertThat(StandTrust.sslContext(trust).protocol).isEqualTo("TLS")
    }

    @Test
    fun `without a stand CA only the system store is used and client checks reject unknown chains`() {
        val trust = StandTrust.trustManager(null)
        val any = HeldCertificate.Builder().commonName("x").build()

        assertThat(trust.acceptedIssuers).isNotEmpty()
        val client = runCatching { trust.checkClientTrusted(arrayOf(any.certificate), "RSA") }
        assertThat(client.isFailure).isTrue()
    }

    @Test
    fun `private keys are refused`() {
        val cert = HeldCertificate.Builder().commonName("k").build()
        val file = tmp.newFile("key.pem").also { it.writeText(cert.certificatePem() + cert.privateKeyPkcs8Pem()) }

        val result = runCatching { StandTrust.trustManager(file) }

        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `screenshot of the login screen is a png`() {
        System.setProperty("java.awt.headless", "true")
        DecomposeSettings.update { it.copy(mainThreadCheckEnabled = false) }
        val koin = koinApplication { modules(consoleModules(desktopPlatform(null))) }.koin
        val lifecycle = LifecycleRegistry()
        val root = koin.createRootComponent(DefaultComponentContext(lifecycle), Dispatchers.Unconfined)
        lifecycle.resume()
        val target = File(tmp.root, "shots/login.png")

        renderScreenshot(root, target, 420, 860)

        val bytes = target.readBytes()
        assertThat(bytes.size).isGreaterThan(5_000)
        assertThat(bytes.take(4)).containsExactly(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
            .inOrder()
    }
}
