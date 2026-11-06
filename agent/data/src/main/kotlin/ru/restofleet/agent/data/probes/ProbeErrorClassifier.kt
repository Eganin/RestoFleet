package ru.restofleet.agent.data.probes

import ru.restofleet.agent.domain.model.ProbeError
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** Сопоставляет исключения и HTTP-статусы классам ошибок проб (1:1 с `ProbeResultDto.error`). */
object ProbeErrorClassifier {
    private const val HTTP_CLIENT_ERROR = 400
    private const val HTTP_SERVER_ERROR = 500
    private const val HTTP_SERVER_ERROR_END = 599
    private const val OKHTTP_CALL_TIMEOUT = "timeout"
    private const val EPERM = "EPERM"

    /** Порядок важен: без сети — всегда [ProbeError.NO_NETWORK]; EPERM приоритетнее [ConnectException]. */
    fun fromThrowable(t: Throwable, networkAvailable: Boolean): ProbeError = when {
        !networkAvailable -> ProbeError.NO_NETWORK
        t is SecurityException || (t is SocketException && t.message?.contains(EPERM) == true) ->
            ProbeError.LOCAL_NETWORK_DENIED
        t is UnknownHostException -> ProbeError.DNS_FAIL
        t is SocketTimeoutException || (t is InterruptedIOException && t.message == OKHTTP_CALL_TIMEOUT) ->
            ProbeError.TIMEOUT
        t is ConnectException -> ProbeError.CONN_REFUSED
        t is SSLException || t is CertificateException -> ProbeError.TLS_ERROR
        else -> ProbeError.OTHER
    }

    /** null — успех (всё ниже 400, включая 204 и редиректы без следования). */
    fun fromStatus(code: Int): ProbeError? = when {
        code < HTTP_CLIENT_ERROR -> null
        code < HTTP_SERVER_ERROR -> ProbeError.HTTP_4XX
        code <= HTTP_SERVER_ERROR_END -> ProbeError.HTTP_5XX
        else -> ProbeError.OTHER
    }
}
