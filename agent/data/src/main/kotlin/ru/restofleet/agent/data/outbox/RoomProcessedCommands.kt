package ru.restofleet.agent.data.outbox

import androidx.room.withTransaction
import ru.restofleet.agent.domain.port.ProcessedCommands

/** Журнал исполненных команд на Room: последние [capacity] по порядку вставки. */
internal class RoomProcessedCommands(
    private val db: AgentDatabase,
    private val capacity: Int = DEFAULT_CAPACITY,
) : ProcessedCommands {
    private val dao = db.processedCommandDao()

    override suspend fun isProcessed(cmdId: String): Boolean = dao.countOf(cmdId) > 0

    override suspend fun markProcessed(cmdId: String) {
        db.withTransaction {
            // INSERT OR IGNORE: повтор не меняет порядок вытеснения
            dao.insert(ProcessedCommandEntity(cmdId = cmdId))
            dao.trimTo(capacity)
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
