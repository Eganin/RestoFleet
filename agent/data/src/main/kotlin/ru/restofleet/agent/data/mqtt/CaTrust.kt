package ru.restofleet.agent.data.mqtt

import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome
import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Пиннинг по CA стенда: хранилище доверия состоит только из переданных сертификатов, системные корни не
 * подмешиваются. Пустой, битый PEM или PEM без сертификата — `Failure(ca_invalid)` (сессия не стартует).
 */
object CaTrust {
    @Suppress("SwallowedException") // причина (текст разбора PEM) не нужна: код `ca_invalid`
    fun trustManagerFactory(pem: ByteArray): Outcome<TrustManagerFactory, DomainError> =
        try {
            val certificates = CertificateFactory.getInstance("X.509")
                .generateCertificates(ByteArrayInputStream(pem))
            if (certificates.isEmpty()) {
                Outcome.Failure(MqttError.CaInvalid)
            } else {
                val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
                certificates.forEachIndexed { index, cert -> store.setCertificateEntry("stand-ca-$index", cert) }
                val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                factory.init(store)
                Outcome.Success(factory)
            }
        } catch (e: GeneralSecurityException) {
            Outcome.Failure(MqttError.CaInvalid)
        }
}
