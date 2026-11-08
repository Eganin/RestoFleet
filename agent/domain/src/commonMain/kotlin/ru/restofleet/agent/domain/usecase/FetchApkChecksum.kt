package ru.restofleet.agent.domain.usecase

import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.port.ApkChecksumSource
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Ошибка [FetchApkChecksum]: адрес APK не подходит для `<url>.sha256`. */
data object InvalidChecksumUrl : DomainError {
    override val code: String = "invalid_url"
}

/** Кнопка «Подтянуть хэш»: SHA-256 из `<адрес APK>.sha256` (только https, без query и fragment). */
class FetchApkChecksum(private val source: ApkChecksumSource) {
    suspend operator fun invoke(apkUrl: String): Outcome<String, DomainError> =
        if (Capabilities.isHttpsUrl(apkUrl) && apkUrl.none { it in QUERY_OR_FRAGMENT }) {
            source.fetch(apkUrl + CHECKSUM_SUFFIX)
        } else {
            Outcome.Failure(InvalidChecksumUrl)
        }

    private companion object {
        const val CHECKSUM_SUFFIX = ".sha256"
        const val QUERY_OR_FRAGMENT = "?#"
    }
}
