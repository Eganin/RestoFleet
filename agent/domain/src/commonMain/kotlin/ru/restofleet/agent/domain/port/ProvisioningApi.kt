package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.error.ProvisionError
import ru.restofleet.agent.domain.model.ProvisionedDevice
import ru.restofleet.shared.kernel.Outcome

/**
 * Погашение кода регистрации на бэкенде: `POST {baseUrl}/api/v1/provision` (FR-AG-10). Реализация сама собирает
 * сведения об устройстве и версию агента, не бросает (кроме настоящей отмены и `Error`) и не пишет код в лог.
 * [baseUrl] уже проверен: `https://хост[:порт]` без завершающего `/`.
 */
fun interface ProvisioningApi {
    suspend fun provision(baseUrl: String, code: String): Outcome<ProvisionedDevice, ProvisionError>
}
