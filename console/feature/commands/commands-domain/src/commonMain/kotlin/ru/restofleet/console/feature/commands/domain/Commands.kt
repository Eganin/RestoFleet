package ru.restofleet.console.feature.commands.domain

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

/** Команды консоли (FR-CN-07). [wire] — значение поля `type` в API. */
enum class CommandType(val wire: String, val params: List<String>, val needsDeviceOwner: Boolean) {
    Ping("ping", emptyList(), false),
    ShowMessage("show_message", listOf("text"), false),
    CollectDiagnostics("collect_diagnostics", emptyList(), false),
    LaunchApp("launch_app", listOf("pkg"), false),
    Reboot("reboot", emptyList(), true),
    LockTask("lock_task", listOf("pkg"), true),
    InstallUpdate("install_update", listOf("package", "url", "sha256"), true),
    ;

    companion object {
        fun fromWire(wire: String): CommandType? = entries.firstOrNull { it.wire == wire }
    }
}

/** Причина, по которой команда выключена; `null` в [CommandSpec.disabled] — доступна. */
enum class Disabled { NeedsDeviceOwner, NotInThisStage }

data class CommandSpec(val type: CommandType, val disabled: Disabled?) {
    val enabled: Boolean get() = disabled == null
}

/**
 * Какие команды доступны устройству. Недоступные видны, но выключены с объяснением (решение макета FR-CN-07):
 * `reboot` и киоск требуют Device Owner; установка и киоск имеют параметры (url, sha256), которых в консоли П нет.
 */
class AvailableCommands {
    operator fun invoke(mode: String?): List<CommandSpec> = CommandType.entries.map { type ->
        val deviceOwner = mode == MODE_DEVICE_OWNER
        CommandSpec(
            type,
            when {
                !type.needsDeviceOwner -> null
                !deviceOwner -> Disabled.NeedsDeviceOwner
                type in DEFERRED -> Disabled.NotInThisStage
                else -> null
            },
        )
    }

    companion object {
        const val MODE_DEVICE_OWNER = "device_owner"
        private val DEFERRED = setOf(CommandType.LockTask, CommandType.InstallUpdate)
    }
}

enum class CommandState(val wire: String, val final: Boolean) {
    Queued("queued", false),
    Delivered("delivered", false),
    Succeeded("succeeded", true),
    Failed("failed", true),
    Unsupported("unsupported", true),
    Expired("expired", true),
    ;

    companion object {
        /** Неизвестный статус считается неоконченным: опрос продолжится до таймаута. */
        fun fromWire(wire: String): CommandState = entries.firstOrNull { it.wire == wire } ?: Queued
    }
}

data class CommandRecord(
    val cmdId: String,
    val type: String,
    val state: CommandState,
    val createdAt: Long,
    val deliveredAt: Long?,
    val finishedAt: Long?,
    val reason: String?,
    val result: Map<String, String>,
)

interface CommandsRepository {
    suspend fun mode(deviceId: String): Outcome<String?, ConsoleError>

    suspend fun send(deviceId: String, type: CommandType, params: Map<String, String>): Outcome<CommandRecord, ConsoleError>

    suspend fun history(deviceId: String, limit: Int): Outcome<List<CommandRecord>, ConsoleError>

    suspend fun status(cmdId: String): Outcome<CommandRecord, ConsoleError>
}
