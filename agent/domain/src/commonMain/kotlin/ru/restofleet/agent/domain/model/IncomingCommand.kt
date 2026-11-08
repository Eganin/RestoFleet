package ru.restofleet.agent.domain.model

import ru.restofleet.agent.domain.command.Command

/**
 * Входящая команда после разбора протокола.
 *
 * - [command] == null и [parseError] == null: тип [rawType] агенту неизвестен, ответ `unsupported`;
 * - [command] == null и [parseError] != null: тип известен, но параметры неверны, ответ `failed`/`invalid_params`
 *   ([parseError] — причина для `reason`/лога).
 *
 * [responseTopic] и [correlationData] — свойства MQTT 5 запроса, ответ публикуется с ними. [expiresAt] — wall clock
 * (правка P2): истёкшую команду не исполняют. Содержит массив, поэтому `equals`/`hashCode` — по содержимому.
 */
data class IncomingCommand(
    val cmdId: String,
    val command: Command?,
    val rawType: String,
    val responseTopic: String?,
    val correlationData: ByteArray?,
    val expiresAt: Long?,
    val parseError: String? = null,
) {
    override fun equals(other: Any?): Boolean = this === other || (
        other is IncomingCommand &&
            cmdId == other.cmdId &&
            command == other.command &&
            rawType == other.rawType &&
            responseTopic == other.responseTopic &&
            correlationData.contentEqualsNullable(other.correlationData) &&
            expiresAt == other.expiresAt &&
            parseError == other.parseError
        )

    override fun hashCode(): Int {
        var result = cmdId.hashCode()
        result = HASH_PRIME * result + (command?.hashCode() ?: 0)
        result = HASH_PRIME * result + rawType.hashCode()
        result = HASH_PRIME * result + (responseTopic?.hashCode() ?: 0)
        result = HASH_PRIME * result + (correlationData?.contentHashCode() ?: 0)
        result = HASH_PRIME * result + (expiresAt?.hashCode() ?: 0)
        result = HASH_PRIME * result + (parseError?.hashCode() ?: 0)
        return result
    }
}

private const val HASH_PRIME = 31
