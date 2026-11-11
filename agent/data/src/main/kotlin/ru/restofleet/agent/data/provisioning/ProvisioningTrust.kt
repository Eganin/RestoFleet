package ru.restofleet.agent.data.provisioning

import okhttp3.OkHttpClient
import ru.restofleet.agent.data.mqtt.CaSource
import ru.restofleet.agent.data.mqtt.CaTrust
import ru.restofleet.agent.domain.error.ProvisionError
import ru.restofleet.shared.kernel.Outcome
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Доверие TLS бэкенда при провижининге (план 3.5): debug — только CA стенда, как у MQTT (системные корни не
 * подмешиваются, нет CA — запрос не уходит, `ca_missing`), release — системное хранилище. Имя хоста проверяет
 * стандартный верификатор OkHttp (только `subjectAltName`).
 */
sealed interface ProvisioningTrust {
    /** Клиент с нужным доверием поверх [base]. */
    fun client(base: OkHttpClient): Outcome<OkHttpClient, ProvisionError>

    data object System : ProvisioningTrust {
        override fun client(base: OkHttpClient): Outcome<OkHttpClient, ProvisionError> = Outcome.Success(base)
    }

    class PinnedStandCa(private val ca: CaSource) : ProvisioningTrust {
        override fun client(base: OkHttpClient): Outcome<OkHttpClient, ProvisionError> {
            // Нет CA или он не разбирается — запрос не уходит (fail closed, как у MQTT)
            val factory = ca.pem()?.let { (CaTrust.trustManagerFactory(it) as? Outcome.Success)?.value }
                ?: return Outcome.Failure(ProvisionError.CaMissing)
            val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
            val context = SSLContext.getInstance(TLS).apply { init(null, arrayOf(manager), null) }
            return Outcome.Success(base.newBuilder().sslSocketFactory(context.socketFactory, manager).build())
        }

        private companion object {
            const val TLS = "TLS"
        }
    }
}
