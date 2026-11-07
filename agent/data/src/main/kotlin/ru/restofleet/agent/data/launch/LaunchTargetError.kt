package ru.restofleet.agent.data.launch

import ru.restofleet.shared.kernel.DomainError

/** Отказ построить цель запуска; [code] — `invalid_url`, `package_not_installed` или `no_launch_intent`. */
data class LaunchTargetError(override val code: String) : DomainError
