package ru.restofleet.agent.data.deviceadmin

import ru.restofleet.shared.kernel.DomainError

/** Ошибка получения SHA-256 из `.sha256`; [code] — `https_required`, `http_<код>`, `checksum_invalid` и т. п. */
data class ChecksumError(override val code: String) : DomainError
