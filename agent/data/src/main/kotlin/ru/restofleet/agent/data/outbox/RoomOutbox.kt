package ru.restofleet.agent.data.outbox

import androidx.room.withTransaction
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.port.Outbox

/** Outbox на Room. Переполнение вытесняет самые старые записи самого низкого приоритета (сначала телеметрию). */
internal class RoomOutbox(
    private val db: AgentDatabase,
    private val capacity: Int = DEFAULT_CAPACITY,
) : Outbox {
    private val dao = db.outboxDao()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /** Вставка и вытеснение лишнего — в одной транзакции: очередь никогда не видна выше вместимости. */
    override suspend fun enqueue(m: OutgoingMessage) {
        db.withTransaction {
            dao.insert(m.toEntity())
            val excess = dao.count() - capacity
            if (excess > 0) dao.evictWorst(excess)
        }
    }

    override suspend fun peek(limit: Int): List<Pair<Long, OutgoingMessage>> =
        dao.peek(limit).map { it.id to it.toMessage() }

    override suspend fun peek(limit: Int, kind: MessageKind): List<Pair<Long, OutgoingMessage>> =
        dao.peekKind(kind.name, limit).map { it.id to it.toMessage() }

    override suspend fun remove(ids: List<Long>) {
        if (ids.isEmpty()) return
        // Лимит переменных SQLite — 999 на старых версиях; пачки в одной транзакции остаются атомарными
        db.withTransaction { ids.chunked(DELETE_CHUNK).forEach { dao.delete(it) } }
    }

    override suspend fun size(): Int = dao.count()

    companion object {
        const val DEFAULT_CAPACITY = 10_000
        private const val DELETE_CHUNK = 500
    }
}

private fun OutgoingMessage.toEntity() = OutboxEntity(
    kind = kind.name,
    priority = kind.priority,
    payload = payload,
    qos = qos,
    retain = retain,
    correlationData = correlationData,
    responseTopic = responseTopic,
    createdAt = createdAt,
)

private fun OutboxEntity.toMessage() = OutgoingMessage(
    kind = MessageKind.valueOf(kind),
    payload = payload,
    qos = qos,
    retain = retain,
    correlationData = correlationData,
    responseTopic = responseTopic,
    createdAt = createdAt,
)
