package ru.restofleet.agent.domain.error

import ru.restofleet.shared.kernel.DomainError

/** Ошибка `FetchApkChecksum`: адрес APK не подходит для `<url>.sha256`. */
data object InvalidChecksumUrl : DomainError {
    override val code: String = "invalid_url"
}
