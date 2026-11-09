package ru.restofleet.agent.domain.model

/**
 * Цель HTTPS-пробы: [id] попадает в результат, [timeoutMs] — потолок всей пробы. [intervalSec], [failThreshold] и
 * [reference] агент пока не использует (цикла проб нет), они хранятся для эхо в `shadow/reported`.
 */
data class ProbeTarget(
    val id: String,
    val url: String,
    val timeoutMs: Long,
    val intervalSec: Int = 30,
    val failThreshold: Int = 3,
    val reference: Boolean = false,
)

/** Класс ошибки пробы; соответствует `ProbeResultDto.error` протокола (на проводе — lowercase). */
enum class ProbeError {
    DNS_FAIL,
    TIMEOUT,
    CONN_REFUSED,
    TLS_ERROR,
    HTTP_4XX,
    HTTP_5XX,
    NO_NETWORK,
    LOCAL_NETWORK_DENIED,
    OTHER,
}

/** Длительности фаз в миллисекундах; null — фаза не наступала (например, TLS у http или сбой DNS). */
data class ProbePhases(val dnsMs: Long?, val connectMs: Long?, val tlsMs: Long?, val ttfbMs: Long?)

/** Итог пробы: [ts] — epoch millis момента завершения, [totalMs] — монотонная длительность. */
data class ProbeResult(
    val targetId: String,
    val ok: Boolean,
    val httpStatus: Int?,
    val error: ProbeError?,
    val totalMs: Long,
    val phases: ProbePhases,
    val ts: Long,
)

/** TCP-уровень: ok или CONN_REFUSED (RST) означают, что хост ответил; HTTP-коды не учитываются. */
val ProbeResult.tcpReachable: Boolean get() = ok || error == ProbeError.CONN_REFUSED
