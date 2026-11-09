package ru.restofleet.agent.domain.port

import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/**
 * Читает SHA-256 из файла-спутника APK (`<url>.sha256`); успех — 64 hex-символа в нижнем регистре. Только для Lab:
 * хэш берётся с того же сервера, что и APK, поэтому он защищает от порчи при загрузке, но не от подмены сервера.
 * Командный путь (`install_update`) этим портом не пользуется.
 */
fun interface ApkChecksumSource {
    suspend fun fetch(checksumUrl: String): Outcome<String, DomainError>
}
