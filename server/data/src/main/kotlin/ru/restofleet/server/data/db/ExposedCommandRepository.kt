package ru.restofleet.server.data.db

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import ru.restofleet.server.data.mapper.ShadowJson
import ru.restofleet.server.domain.command.Command
import ru.restofleet.server.domain.command.CommandReasons
import ru.restofleet.server.domain.command.CommandRepository
import ru.restofleet.server.domain.command.CommandStatus
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.protocol.RfJson

/** `commands`: истечение и выдача REST-опросу — одним `UPDATE` (без гонки с ответом устройства). */
internal class ExposedCommandRepository : CommandRepository {
    private val resultSerializer = MapSerializer(String.serializer(), String.serializer())
    private val pending = listOf(CommandStatus.QUEUED.code, CommandStatus.DELIVERED.code)

    override suspend fun insert(command: Command) {
        CommandsTable.insert {
            it[cmdId] = command.cmdId
            it[deviceId] = command.deviceId.value
            it[type] = command.type
            it[params] = ShadowJson.encode(command.params)
            it[createdAt] = command.createdAt.toTimestamp()
            it[expiresAt] = command.expiresAt.toTimestamp()
            it.state(command)
        }
    }

    override suspend fun find(cmdId: String): Command? =
        CommandsTable.selectAll().where { CommandsTable.cmdId eq cmdId }.singleOrNull()?.toCommand()

    override suspend fun findForUpdate(cmdId: String): Command? = CommandsTable.selectAll()
        .where { CommandsTable.cmdId eq cmdId }
        .forUpdate(ForUpdateOption.ForUpdate)
        .singleOrNull()
        ?.toCommand()

    override suspend fun update(command: Command) {
        CommandsTable.update({ CommandsTable.cmdId eq command.cmdId }) { it.state(command) }
    }

    override suspend fun expireDue(now: Long): Int = CommandsTable.update({
        (CommandsTable.status inList pending) and (CommandsTable.expiresAt less now.toTimestamp())
    }) {
        it[status] = CommandStatus.EXPIRED.code
        it[finishedAt] = now.toTimestamp()
        it[reason] = CommandReasons.EXPIRED
    }

    override suspend fun deliverQueued(deviceId: DeviceId, now: Long): List<Command> = CommandsTable
        .updateReturning(
            returning = CommandsTable.columns,
            where = {
                (CommandsTable.deviceId eq deviceId.value) and
                    (CommandsTable.status eq CommandStatus.QUEUED.code) and
                    (CommandsTable.expiresAt greater now.toTimestamp())
            },
        ) {
            it[status] = CommandStatus.DELIVERED.code
            it[deliveredAt] = now.toTimestamp()
        }
        .map { it.toCommand() }
        .sortedWith(compareBy({ it.createdAt }, { it.cmdId }))

    private fun org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>.state(command: Command) {
        this[CommandsTable.status] = command.status.code
        this[CommandsTable.deliveredAt] = command.deliveredAt?.toTimestamp()
        this[CommandsTable.finishedAt] = command.finishedAt?.toTimestamp()
        this[CommandsTable.result] = command.result?.let { RfJson.encodeToString(resultSerializer, it) }
        this[CommandsTable.reason] = command.reason
    }

    private fun ResultRow.toCommand() = Command(
        cmdId = this[CommandsTable.cmdId],
        deviceId = DeviceId.parse(this[CommandsTable.deviceId]) ?: error("invalid device id in database"),
        type = this[CommandsTable.type],
        params = ShadowJson.decode(this[CommandsTable.params]),
        status = CommandStatus.fromCode(this[CommandsTable.status]) ?: error("unknown command status in database"),
        createdAt = this[CommandsTable.createdAt].toMillis(),
        expiresAt = this[CommandsTable.expiresAt].toMillis(),
        deliveredAt = this[CommandsTable.deliveredAt]?.toMillis(),
        finishedAt = this[CommandsTable.finishedAt]?.toMillis(),
        result = this[CommandsTable.result]?.let { RfJson.decodeFromString(resultSerializer, it) },
        reason = this[CommandsTable.reason],
    )
}
