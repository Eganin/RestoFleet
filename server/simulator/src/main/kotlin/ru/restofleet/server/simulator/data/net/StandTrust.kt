package ru.restofleet.server.simulator.data.net

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Доверие только CA стенда (`deploy/certs/out/public/ca.crt`), как у агента в debug и у бэкенда. */
object StandTrust {
    fun fromCaFile(file: File): TrustManagerFactory {
        val ca = file.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("restofleet-stand-ca", ca)
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
    }

    fun x509(factory: TrustManagerFactory): X509TrustManager =
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}
