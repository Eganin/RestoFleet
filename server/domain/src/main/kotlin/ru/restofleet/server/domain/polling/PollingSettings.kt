package ru.restofleet.server.domain.polling

/** Настройки REST-polling (эксперимент, реш. 214). */
data class PollingSettings(
    /** Без опроса дольше этого устройство `offline`: 3 × интервал опроса (по умолчанию 3 × 30 с). */
    val staleAfterMillis: Long = DEFAULT_STALE_AFTER_MS,
) {
    companion object {
        const val DEFAULT_STALE_AFTER_MS = 90_000L
    }
}
