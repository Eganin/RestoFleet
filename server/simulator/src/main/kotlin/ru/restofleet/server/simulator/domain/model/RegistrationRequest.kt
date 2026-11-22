package ru.restofleet.server.simulator.domain.model

/** Сколько устройств должно быть в кэше и как регистрировать недостающие. */
data class RegistrationRequest(
    val devices: Int,
    val mix: RoleMix = RoleMix(),
    val parallelism: Int = DEFAULT_PARALLELISM,
    val maxAttempts: Int = DEFAULT_ATTEMPTS,
    val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
) {
    init {
        require(devices >= 1) { "devices must be >= 1" }
        require(parallelism >= 1 && maxAttempts >= 1 && retryDelayMs >= 0) { "invalid registration limits" }
    }

    private companion object {
        const val DEFAULT_PARALLELISM = 8
        const val DEFAULT_ATTEMPTS = 5
        const val DEFAULT_RETRY_DELAY_MS = 1_000L
    }
}
