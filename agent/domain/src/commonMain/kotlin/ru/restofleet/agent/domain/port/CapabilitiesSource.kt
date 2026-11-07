package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.capability.Capabilities

/** Текущие возможности агента (режим, API, выданные разрешения); читаются на каждую команду. */
fun interface CapabilitiesSource {
    suspend fun current(): Capabilities
}
