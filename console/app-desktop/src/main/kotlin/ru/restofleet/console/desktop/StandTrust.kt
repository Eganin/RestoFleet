package ru.restofleet.console.desktop

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Доверие к CA стенда на Desktop (реш. 422, 423): системные сертификаты плюс один PEM из `-Drf.standCa=<файл>`.
 * Без свойства — только системные. Файл должен быть публичным сертификатом (ключи отвергаются).
 */
object StandTrust {
    fun trustManager(standCaPem: File?): X509TrustManager {
        val system = defaultManager(null)
        if (standCaPem == null) return system
        val pem = standCaPem.readText()
        require("PRIVATE KEY" !in pem) { "stand CA file must not contain a private key" }
        val certificate = standCaPem.inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("stand-ca", certificate)
        }
        return CompositeTrustManager(listOf(defaultManager(store), system))
    }

    fun sslContext(trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }

    private fun defaultManager(store: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** Цепочка принимается, если её принимает любой из менеджеров. */
    internal class CompositeTrustManager(private val delegates: List<X509TrustManager>) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            check { it.checkClientTrusted(chain, authType) }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            check { it.checkServerTrusted(chain, authType) }

        override fun getAcceptedIssuers(): Array<X509Certificate> =
            delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()

        @Suppress("TooGenericExceptionCaught")
        private fun check(verify: (X509TrustManager) -> Unit) {
            var last: Exception? = null
            for (delegate in delegates) {
                try {
                    verify(delegate)
                    return
                } catch (e: java.security.cert.CertificateException) {
                    last = e
                }
            }
            throw last ?: java.security.cert.CertificateException("no trust managers")
        }
    }
}
