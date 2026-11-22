package ru.restofleet.console.feature.shadow.domain

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.shadow.ShadowDocument

/** Тень устройства (FR-CN-06): желаемое, сообщённое и разница между ними. */
data class ShadowState(
    val deviceId: String,
    val desired: ShadowDocument,
    val desiredVersion: Long,
    val desiredUpdatedAt: Long,
    val reported: ShadowDocument?,
    val reportedVersion: Long?,
    val errors: List<String>,
    val delta: ShadowDocument,
    val pending: Boolean,
    val published: Boolean?,
    val loadedAt: Long,
)

interface ShadowRepository {
    suspend fun load(deviceId: String): Outcome<ShadowState, ConsoleError>

    /** Merge-patch: объекты сливаются, списки и значения заменяются. */
    suspend fun put(deviceId: String, patch: ShadowDocument): Outcome<ShadowState, ConsoleError>
}

/** JSON-представление тени: порт, реализуется в `-data` (домен не знает о сериализации). */
interface ShadowCodec {
    fun render(document: ShadowDocument): String

    fun parse(text: String): Outcome<ShadowDocument, ConsoleError>
}

class LoadShadow(private val repository: ShadowRepository) {
    suspend operator fun invoke(deviceId: String): Outcome<ShadowState, ConsoleError> = repository.load(deviceId)
}
