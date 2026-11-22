package ru.restofleet.server.domain.common

import ru.restofleet.shared.kernel.DomainError

/**
 * Ошибки бэкенда (план 2.1). [code] — стабильный машиночитаемый код: `server:api` превращает ошибку в HTTP-статус и
 * тело `{code, message}`. Инфраструктурные сбои (БД недоступна) идут исключениями мимо `Outcome` — их ловит api
 * (500/503); `Outcome` несёт только ожидаемые исходы бизнес-логики.
 */
sealed interface ServerError : DomainError {
    /** Сущность [entity] с ключом [id] не найдена (404). */
    data class NotFound(val entity: String, val id: String) : ServerError {
        override val code: String = "not_found"
    }

    /** Конфликт с текущим состоянием, например занятый код ресторана (409). */
    data class Conflict(val reason: String) : ServerError {
        override val code: String = "conflict"
    }

    /** Неверное значение поля [field]; [reason] — машиночитаемая причина (400). */
    data class Validation(val field: String, val reason: String) : ServerError {
        override val code: String = "validation"
    }

    /** Неверные учётные данные или токен (401). Причину наружу не раскрываем. */
    data object Unauthorized : ServerError {
        override val code: String = "unauthorized"
    }

    /** Код регистрации не найден (401/404 — решает api). */
    data object TokenInvalid : ServerError {
        override val code: String = "token_invalid"
    }

    /** Код регистрации просрочен (410). */
    data object TokenExpired : ServerError {
        override val code: String = "token_expired"
    }

    /** Код регистрации уже погашен (409). */
    data object TokenUsed : ServerError {
        override val code: String = "token_used"
    }

    /** Устройство выведено из эксплуатации (410). */
    data class DeviceDecommissioned(val deviceId: String) : ServerError {
        override val code: String = "device_decommissioned"
    }

    /** Брокер не ответил или отказал (503); [operation] — что не удалось (`create_client`, `publish`…). */
    data class BrokerUnavailable(val operation: String) : ServerError {
        override val code: String = "broker_unavailable"
    }
}
