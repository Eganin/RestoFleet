package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.capability.LaunchStrategy
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome

/** Запуск рабочего приложения (или адреса в браузере) выбранной стратегией. */
fun interface AppLauncher {
    suspend fun launch(pkg: String, url: String?, strategy: LaunchStrategy): Outcome<Map<String, String>, DomainError>
}
