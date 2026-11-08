package ru.restofleet.agent.domain.port

import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Читает SHA-256 из файла-спутника APK (`<url>.sha256`); успех — 64 hex-символа в нижнем регистре. */
fun interface ApkChecksumSource {
    suspend fun fetch(checksumUrl: String): Outcome<String, DomainError>
}
