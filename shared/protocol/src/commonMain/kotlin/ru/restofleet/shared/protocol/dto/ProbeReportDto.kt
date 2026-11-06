package ru.restofleet.shared.protocol.dto

import kotlinx.serialization.Serializable

/** `rf/v1/dev/{id}/probe`. Поэтапные времена (`dnsMs`…`ttfbMs`) — с этапа П, в MVP заполняются, если измерены. */
@Serializable
data class ProbeReportDto(
    val ts: Long,
    val results: List<ProbeResultDto>,
)

@Serializable
data class ProbeResultDto(
    val id: String,
    val ok: Boolean,
    val status: Int? = null,
    /**
     * Класс ошибки: `dns_fail`, `timeout`, `conn_refused`, `tls_error`, `http_4xx`, `http_5xx`, `no_network`,
     * `local_network_denied`, `other`. Строка, а не enum: неизвестный код от более новой стороны не ломает декодирование.
     */
    val error: String? = null,
    val totalMs: Long,
    val dnsMs: Long? = null,
    val connectMs: Long? = null,
    val tlsMs: Long? = null,
    val ttfbMs: Long? = null,
)
