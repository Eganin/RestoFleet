package ru.restofleet.agent.domain.error

import ru.restofleet.shared.kernel.DomainError

/** Отказ при настройке учётки брокера; коды стабильны и не содержат введённых значений. */
sealed interface BrokerConfigError : DomainError {
    data object InvalidHost : BrokerConfigError {
        override val code: String = "invalid_host"
    }

    data object InvalidPort : BrokerConfigError {
        override val code: String = "invalid_port"
    }

    data object InvalidDeviceId : BrokerConfigError {
        override val code: String = "invalid_device_id"
    }

    data object EmptyPassword : BrokerConfigError {
        override val code: String = "empty_password"
    }

    data object StorageFailed : BrokerConfigError {
        override val code: String = "storage_failed"
    }
}
