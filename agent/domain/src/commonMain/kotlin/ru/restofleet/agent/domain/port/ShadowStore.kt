package ru.restofleet.agent.domain.port

import kotlinx.coroutines.flow.Flow
import ru.restofleet.agent.domain.model.DesiredShadow

/** Последняя применённая конфигурация; переживает рестарт. */
interface ShadowStore {
    suspend fun appliedVersion(): Long?
    suspend fun save(shadow: DesiredShadow)

    /** Сохранённая тень или `null` (нет или нечитаема). */
    suspend fun load(): DesiredShadow?

    /** Текущая сохранённая тень сразу, затем после каждого [save]; нужна циклам, которые перенастраиваются на лету. */
    fun observe(): Flow<DesiredShadow?>
}
