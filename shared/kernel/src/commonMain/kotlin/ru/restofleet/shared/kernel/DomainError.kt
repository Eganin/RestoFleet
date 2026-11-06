package ru.restofleet.shared.kernel

/**
 * Базовый тип доменных ошибок. Каждый domain-модуль объявляет свою sealed-иерархию поверх него;
 * [code] — стабильный машиночитаемый код (уходит в `reason` протокола и в HTTP-маппинг).
 */
interface DomainError {
    val code: String
}

/** Непредвиденная ошибка, которую data-слой не смог классифицировать. */
data class UnexpectedError(val details: String) : DomainError {
    override val code: String = "unexpected"
}
