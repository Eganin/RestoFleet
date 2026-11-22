package ru.restofleet.console.core.common

import ru.restofleet.shared.kernel.DomainError

/** Ошибка консоли: общая для всех фич, не зависит от Ktor и сериализации. Тексты строит UI (мапперы). */
sealed interface ConsoleError : DomainError {
    /** Нет связи с сервером или таймаут. */
    data object Network : ConsoleError {
        override val code: String = "network"
    }

    /** Токен недействителен и обновить его не удалось (или неверный логин и пароль при входе). */
    data object Unauthorized : ConsoleError {
        override val code: String = "unauthorized"
    }

    data object Forbidden : ConsoleError {
        override val code: String = "forbidden"
    }

    data object NotFound : ConsoleError {
        override val code: String = "not_found"
    }

    /** Состояние уже изменилось (повторное подтверждение, команда офлайн-устройству и т. п.). */
    data class Conflict(val reason: String?) : ConsoleError {
        override val code: String = "conflict"
    }

    /** Сервер отклонил значение поля; [field] — имя поля контракта. */
    data class Validation(val field: String?, val message: String) : ConsoleError {
        override val code: String = "validation"
    }

    /** Лимит запросов; [retryAfterSec] — подсказка сервера, если была. */
    data class RateLimited(val retryAfterSec: Int?) : ConsoleError {
        override val code: String = "rate_limited"
    }

    /** Ответ 5xx или непонятный ответ. */
    data class Server(val status: Int, val serverCode: String?) : ConsoleError {
        override val code: String = "server"
    }

    /** Адрес сервера не прошёл проверку (нужен https). */
    data class InvalidAddress(val reason: String) : ConsoleError {
        override val code: String = "invalid_address"
    }

    /** Локальная проверка ввода до отправки (например, форма тени). */
    data class InvalidInput(val field: String?, val message: String) : ConsoleError {
        override val code: String = "invalid_input"
    }

    data class Unexpected(val details: String) : ConsoleError {
        override val code: String = "unexpected"
    }
}
