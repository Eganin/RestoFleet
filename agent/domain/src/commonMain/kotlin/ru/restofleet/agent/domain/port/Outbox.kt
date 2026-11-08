package ru.restofleet.agent.domain.port

import ru.restofleet.agent.domain.model.OutgoingMessage

/** Очередь неотправленных сообщений (переживает рестарт процесса). Идентификатор записи выдаёт хранилище. */
interface Outbox {
    suspend fun enqueue(m: OutgoingMessage)

    /** До [limit] записей вместе с идентификаторами, в порядке досылки. */
    suspend fun peek(limit: Int): List<Pair<Long, OutgoingMessage>>

    suspend fun remove(ids: List<Long>)

    suspend fun size(): Int
}
