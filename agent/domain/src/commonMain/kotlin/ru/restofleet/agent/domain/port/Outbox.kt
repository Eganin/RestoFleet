package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage

/** Очередь неотправленных сообщений (переживает рестарт процесса). Идентификатор записи выдаёт хранилище. */
interface Outbox {
    suspend fun enqueue(m: OutgoingMessage)

    /**
     * До [limit] записей вместе с идентификаторами, в порядке досылки: по приоритету ([MessageKind.priority], 0 —
     * первым), внутри приоритета — в порядке постановки.
     */
    suspend fun peek(limit: Int): List<Pair<Long, OutgoingMessage>>

    /** До [limit] записей вида [kind] в порядке постановки (итоги команд мимо очереди телеметрии, решение 172). */
    suspend fun peek(limit: Int, kind: MessageKind): List<Pair<Long, OutgoingMessage>>

    suspend fun remove(ids: List<Long>)

    suspend fun size(): Int
}
