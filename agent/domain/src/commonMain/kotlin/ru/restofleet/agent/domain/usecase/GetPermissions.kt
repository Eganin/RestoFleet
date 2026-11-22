package ru.restofleet.agent.domain.usecase

import ru.restofleet.agent.domain.capability.Permission
import ru.restofleet.agent.domain.port.CapabilitiesSource

/** Выданные агенту разрешения — для шага мастера «разрешения» (FR-AG-04) и экрана статуса. */
class GetPermissions(private val caps: CapabilitiesSource) {
    suspend operator fun invoke(): Set<Permission> = caps.current().granted
}
