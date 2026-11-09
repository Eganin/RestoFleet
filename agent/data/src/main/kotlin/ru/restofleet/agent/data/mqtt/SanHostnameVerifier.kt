package ru.restofleet.agent.data.mqtt

import java.net.InetAddress
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession

/**
 * Проверка имени хоста по `subjectAltName` листового сертификата (RFC 6125): IP-адрес сверяется с записями типа
 * IP, имя с записями DNS (регистр не важен, `*` закрывает ровно одну левую метку). `CN` не используется: сертификат
 * без SAN отвергается. HiveMQ полагается на верификатор, заданный в `MqttClientSslConfig`, поэтому проверка явная.
 */
internal object SanHostnameVerifier : HostnameVerifier {
    private const val SAN_DNS = 2
    private const val SAN_IP = 7
    private const val IPV4_PARTS = 4

    override fun verify(hostname: String, session: SSLSession): Boolean {
        val names = subjectAltNames(session)
        return when {
            names == null -> false
            isIpLiteral(hostname) -> names.any { it[0] == SAN_IP && sameIp(it[1] as? String, hostname) }
            else -> names.any { it[0] == SAN_DNS && dnsMatches(it[1] as? String, hostname) }
        }
    }

    @Suppress("SwallowedException") // нет сертификата или SAN не разобрать: имя не подтверждено
    private fun subjectAltNames(session: SSLSession): Collection<List<*>>? =
        try {
            (session.peerCertificates.firstOrNull() as? X509Certificate)?.subjectAlternativeNames
        } catch (e: SSLPeerUnverifiedException) {
            null
        } catch (e: CertificateParsingException) {
            null
        }

    private fun isIpLiteral(host: String): Boolean {
        val parts = host.split('.')
        val ipv4 = parts.size == IPV4_PARTS && parts.all { it.toIntOrNull() != null }
        return host.contains(':') || ipv4
    }

    private fun sameIp(san: String?, host: String): Boolean =
        san != null && InetAddress.getByName(san) == InetAddress.getByName(host)

    private fun dnsMatches(pattern: String?, host: String): Boolean {
        val p = pattern?.lowercase()
        val h = host.lowercase()
        val labelEnd = h.indexOf('.')
        return when {
            p == null -> false
            !p.startsWith("*.") -> p == h
            else -> labelEnd > 0 && h.substring(labelEnd + 1) == p.substring(2)
        }
    }
}
