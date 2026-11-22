package ru.restofleet.shared.probes

/**
 * Класс ошибки проверки доступности (FR-AG-32; ТЗ 7.2). [code] — значение `ProbeResultDto.error` и
 * `probe_state.error` на проводе. Классификация исключений платформы (errno, `SSLException`) живёт в data-слое агента;
 * здесь — чистые правила, общие для агента, бэкенда и симулятора (реш. 227).
 */
enum class ProbeErrorClass(val code: String) {
    DNS_FAIL("dns_fail"),
    TIMEOUT("timeout"),
    CONN_REFUSED("conn_refused"),
    TLS_ERROR("tls_error"),
    HTTP_4XX("http_4xx"),
    HTTP_5XX("http_5xx"),
    NO_NETWORK("no_network"),
    LOCAL_NETWORK_DENIED("local_network_denied"),
    OTHER("other"),
    ;

    companion object {
        /** `null` — ошибки нет; неизвестный код от более новой стороны — [OTHER]. */
        fun fromCode(code: String?): ProbeErrorClass? =
            code?.let { wire -> entries.firstOrNull { it.code == wire } ?: OTHER }
    }
}

private const val HTTP_CLIENT_ERROR = 400
private const val HTTP_SERVER_ERROR = 500
private const val HTTP_SERVER_ERROR_END = 599

/**
 * Класс ошибки по HTTP-статусу [code]; `null` — успех. Без [expected] успех — всё ниже 400 (включая 204 и редиректы
 * без следования); с [expected] — только точное совпадение (FR-AG-30, реш. 219). Неуспешный код: 4xx — [HTTP_4XX],
 * 5xx — [HTTP_5XX], прочее (неожиданный 2xx/3xx, коды вне 100..599) — [OTHER].
 */
fun classifyHttpStatus(code: Int, expected: Int? = null): ProbeErrorClass? {
    val success = if (expected == null) code < HTTP_CLIENT_ERROR else code == expected
    return when {
        success -> null
        code in HTTP_CLIENT_ERROR until HTTP_SERVER_ERROR -> ProbeErrorClass.HTTP_4XX
        code in HTTP_SERVER_ERROR..HTTP_SERVER_ERROR_END -> ProbeErrorClass.HTTP_5XX
        else -> ProbeErrorClass.OTHER
    }
}
