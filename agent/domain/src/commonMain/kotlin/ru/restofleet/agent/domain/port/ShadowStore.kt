package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.model.DesiredShadow

/** Последняя применённая конфигурация; переживает рестарт. */
interface ShadowStore {
    suspend fun appliedVersion(): Long?
    suspend fun save(shadow: DesiredShadow)
}
