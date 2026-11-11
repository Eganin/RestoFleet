package ru.restofleet.agent.domain.error

import ru.restofleet.shared.kernel.DomainError

/**
 * Отказ провижининга по коду (FR-AG-10, контракт K2): коды стабильны и не содержат введённых значений (ни кода
 * регистрации, ни адреса). Отказы бэкенда — по его `code` (реш. 247), сетевые — по классу сбоя.
 */
sealed interface ProvisionError : DomainError {
    /** Адрес стенда не `https://хост[:порт]` (без пути, логина, пробелов). */
    data object InvalidBaseUrl : ProvisionError {
        override val code: String = "invalid_base_url"
    }

    data object EmptyCode : ProvisionError {
        override val code: String = "empty_code"
    }

    /** 401 `token_invalid`: такого кода нет. */
    data object CodeInvalid : ProvisionError {
        override val code: String = "token_invalid"
    }

    /** 409 `token_used`: код уже погашен. */
    data object CodeUsed : ProvisionError {
        override val code: String = "token_used"
    }

    /** 410 `token_expired`: срок кода (24 ч) истёк. */
    data object CodeExpired : ProvisionError {
        override val code: String = "token_expired"
    }

    /** 429: лимит попыток; [retryAfterSec] — из `Retry-After`, если есть. */
    data class RateLimited(val retryAfterSec: Long?) : ProvisionError {
        override val code: String = "rate_limited"
    }

    /** 503 и прочие 5xx: брокер или БД стенда недоступны, код не погашен — можно повторить. */
    data object ServerUnavailable : ProvisionError {
        override val code: String = "server_unavailable"
    }

    /** Прочий отказ бэкенда (400 `validation` и т. п.); [reason] — его `code` или HTTP-статус. */
    data class Rejected(val reason: String) : ProvisionError {
        override val code: String = "rejected"
    }

    /** Нет сети, хост не найден или не отвечает. */
    data object NoNetwork : ProvisionError {
        override val code: String = "no_network"
    }

    data object Timeout : ProvisionError {
        override val code: String = "timeout"
    }

    /** Сертификат стенда не доверен или рукопожатие не удалось. */
    data object Tls : ProvisionError {
        override val code: String = "tls_error"
    }

    /** Debug-сборка без CA стенда: пиннинг невозможен, запрос не отправляется. */
    data object CaMissing : ProvisionError {
        override val code: String = "ca_missing"
    }

    /** Ответ 201 не разобран: не тот JSON, недопустимый `deviceId`, неизвестная роль. */
    data object InvalidResponse : ProvisionError {
        override val code: String = "invalid_response"
    }

    /** Учётка получена, но не сохранилась в Keystore: код уже погашен, нужен новый. */
    data object StorageFailed : ProvisionError {
        override val code: String = "storage_failed"
    }

    /** Непредвиденный сбой клиента; в коде — только класс исключения. */
    data class Failed(val cause: String) : ProvisionError {
        override val code: String = "failed"
    }
}
