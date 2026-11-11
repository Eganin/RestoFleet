package ru.restofleet.agent.domain.model

import ru.restofleet.shared.probes.ProbeErrorClass

/**
 * Цель HTTPS-пробы: [id] попадает в результат, [timeoutMs] — потолок всей пробы, [intervalSec] — период плановых проб
 * (не чаще минимума `ProbeSchedule`), [failThreshold] — K неудач подряд до `down` (FR-AG-34). [expectedStatus] — точный
 * ожидаемый HTTP-код (FR-AG-30, реш. 219), `null` — успех любой код ниже 400. [reference] агент не использует, поле
 * возвращается в `shadow/reported` как эхо, как и все остальные.
 */
data class ProbeTarget(
    val id: String,
    val url: String,
    val timeoutMs: Long,
    val intervalSec: Int = 30,
    val failThreshold: Int = 3,
    val reference: Boolean = false,
    val expectedStatus: Int? = null,
)

/**
 * Класс ошибки пробы — общий [ProbeErrorClass] из `shared:probes` (реш. 227); псевдоним сохраняет имя MVP, КМ3 может
 * перевести агент на `ProbeErrorClass` напрямую.
 */
typealias ProbeError = ProbeErrorClass

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
