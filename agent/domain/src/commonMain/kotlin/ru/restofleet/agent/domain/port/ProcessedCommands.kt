package ru.restofleet.agent.domain.port

/**
 * Журнал уже исполненных команд: повторная доставка QoS 1 не должна исполнять команду дважды.
 * Хранит последние 200 `cmdId`; при переполнении вытесняется самый старый.
 */
interface ProcessedCommands {
    suspend fun isProcessed(cmdId: String): Boolean
    suspend fun markProcessed(cmdId: String)
}
