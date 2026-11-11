package ru.restofleet.agent.data.probes

import ru.restofleet.agent.domain.model.ProbeError
import ru.restofleet.shared.probes.classifyHttpStatus
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** Сопоставляет исключения и HTTP-статусы классам ошибок проб (1:1 с `ProbeResultDto.error`). */
object ProbeErrorClassifier {
    private const val OKHTTP_CALL_TIMEOUT = "timeout"
    private const val MAX_CAUSE_DEPTH = 8
    private val DENIED = listOf("eperm", "eacces", "operation not permitted", "permission denied")
    private val REFUSED = listOf("econnrefused", "connection refused")
    private val SOCKET_TIMEOUT = listOf("etimedout", "timed out")
    private val UNREACHABLE = listOf("enetunreach", "network is unreachable")

    /**
     * Порядок важен: без сети — всегда [ProbeError.NO_NETWORK]; запрет (EPERM/EACCES) приоритетнее прочего.
     * Android (libcore) оборачивает разные errno в один [ConnectException], поэтому сокетные сбои различаются по
     * тексту сообщения (всей цепочки причин, без учёта регистра): ECONNREFUSED -> CONN_REFUSED, ETIMEDOUT -> TIMEOUT,
     * ENETUNREACH -> NO_NETWORK; EHOSTUNREACH и неопознанное -> OTHER.
     */
    fun fromThrowable(t: Throwable, networkAvailable: Boolean): ProbeError {
        val chain = generateSequence(t) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        val socketText = chain.filterIsInstance<SocketException>().joinToString(" ") { it.message.orEmpty() }
            .lowercase()
        return when {
            !networkAvailable -> ProbeError.NO_NETWORK
            chain.any { it is SecurityException } || DENIED.any { it in socketText } ->
                ProbeError.LOCAL_NETWORK_DENIED
            chain.any { it is UnknownHostException } -> ProbeError.DNS_FAIL
            chain.any { it is SocketTimeoutException || isCallTimeout(it) } -> ProbeError.TIMEOUT
            REFUSED.any { it in socketText } -> ProbeError.CONN_REFUSED
            SOCKET_TIMEOUT.any { it in socketText } -> ProbeError.TIMEOUT
            UNREACHABLE.any { it in socketText } -> ProbeError.NO_NETWORK
            chain.any { it is SSLException || it is CertificateException } -> ProbeError.TLS_ERROR
            else -> ProbeError.OTHER
        }
    }

    private fun isCallTimeout(t: Throwable) = t is InterruptedIOException && t.message == OKHTTP_CALL_TIMEOUT

    /** null — успех (всё ниже 400, включая 204 и редиректы без следования); правило — общее из `shared:probes`. */
    fun fromStatus(code: Int): ProbeError? = classifyHttpStatus(code)
}
