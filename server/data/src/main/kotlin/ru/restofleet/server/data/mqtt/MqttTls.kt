package ru.restofleet.server.data.mqtt

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory

/** Доверие только к CA стенда (PEM): системные CA не используются. */
object MqttTls {
    fun trustOnly(caFile: String): TrustManagerFactory {
        val certificates = File(caFile).inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificates(it)
        }
        require(certificates.isNotEmpty()) { "no certificates in $caFile" }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { i, cert -> setCertificateEntry("ca-$i", cert) }
        }
        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
    }
}
