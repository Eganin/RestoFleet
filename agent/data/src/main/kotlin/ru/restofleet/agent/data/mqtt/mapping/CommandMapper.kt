package ru.restofleet.agent.data.mqtt.mapping

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import ru.restofleet.agent.domain.command.Command
import ru.restofleet.agent.domain.command.Level
import ru.restofleet.agent.domain.model.IncomingCommand
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.RfJson
import ru.restofleet.shared.protocol.dto.CommandRequestDto

/**
 * `cmd/req` -> [IncomingCommand]. Ничего не бросает:
 * - битый JSON и отсутствие `cmdId`/`type` — [InvalidPayload] (3.4 ответит `failed`/`invalid_payload`);
 * - неизвестный `type` — `command = null`, `parseError = null` (ответ `unsupported`);
 * - известный `type` с неверными параметрами — `command = null`, `parseError` с причиной (`failed`/`invalid_params`).
 */
internal object CommandMapper {
    fun parse(
        payload: ByteArray,
        responseTopic: String? = null,
        correlationData: ByteArray? = null,
    ): Outcome<IncomingCommand, InvalidPayload> = try {
        val dto = RfJson.decodeFromString(CommandRequestDto.serializer(), payload.decodeToString())
        Outcome.Success(fromDto(dto, responseTopic, correlationData))
    } catch (e: SerializationException) {
        Outcome.Failure(InvalidPayload("cmd/req: ${e.message}"))
    }

    fun fromDto(
        dto: CommandRequestDto,
        responseTopic: String? = null,
        correlationData: ByteArray? = null,
    ): IncomingCommand {
        val parsed = parseCommand(dto.type, dto.params)
        return IncomingCommand(
            cmdId = dto.cmdId,
            command = (parsed as? Parsed.Known)?.command,
            rawType = dto.type,
            responseTopic = responseTopic,
            correlationData = correlationData,
            expiresAt = dto.expiresAt,
            parseError = (parsed as? Parsed.Invalid)?.reason,
        )
    }

    private sealed interface Parsed {
        data class Known(val command: Command) : Parsed
        data class Invalid(val reason: String) : Parsed
        data object UnknownType : Parsed
    }

    /** Ошибка параметров внутри разбора: перехватывается в [parseCommand], наружу не уходит. */
    private class InvalidParams(val reason: String) : Exception(reason)

    @Suppress("CyclomaticComplexMethod")
    private fun parseCommand(type: String, p: JsonObject): Parsed = try {
        Parsed.Known(
            when (type) {
                "ping" -> Command.Ping
                "show_message" -> Command.ShowMessage(p.text("text"), p.level())
                "launch_app" -> Command.LaunchApp(p.text("pkg"), p.optionalText("url"))
                "beep" -> Command.Beep(p.positiveLong("durationMs"))
                "reboot" -> Command.Reboot
                "install_update" -> Command.InstallApk(p.text("package"), p.text("url"), p.text("sha256"))
                "lock_task" -> Command.LockTask(p.text("pkg"), p.optionalText("url"))
                "run_probe" -> Command.RunProbe(p.text("target"))
                "collect_diagnostics" -> Command.CollectDiagnostics
                else -> return Parsed.UnknownType
            },
        )
    } catch (e: InvalidParams) {
        Parsed.Invalid(e.reason)
    }

    private fun JsonObject.string(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        return if (value.isString) value.content else null
    }

    private fun JsonObject.text(key: String): String =
        string(key)?.takeIf { it.isNotBlank() } ?: throw InvalidParams("$key: non-empty string required")

    private fun JsonObject.optionalText(key: String): String? =
        if (key !in this) null else string(key) ?: throw InvalidParams("$key: string expected")

    private fun JsonObject.positiveLong(key: String): Long {
        val value = this[key] as? JsonPrimitive
        return value?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }
            ?: throw InvalidParams("$key: positive integer required")
    }

    /** Без `level` — [Level.INFO]; неизвестное значение — ошибка параметров. */
    private fun JsonObject.level(): Level {
        if ("level" !in this) return Level.INFO
        return Level.entries.firstOrNull { it.name.lowercase() == string("level") }
            ?: throw InvalidParams("level: one of info, warning, critical expected")
    }
}
