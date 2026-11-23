package ru.restofleet.console.feature.shadow.domain

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

/**
 * Ожидание применения после сохранения (V-4): события тени в WS нет, поэтому тень перечитывается каждые
 * [intervalMs], пока `pending`, но не больше [attempts] раз (≈ 60 с — интервал синхронизации агента с запасом).
 * Сбой чтения пропускается — следующая попытка по расписанию.
 */
class AwaitShadowApplied(
    private val repository: ShadowRepository,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val attempts: Int = DEFAULT_ATTEMPTS,
) {
    operator fun invoke(deviceId: String): Flow<ShadowState> = flow {
        repeat(attempts) {
            delay(intervalMs)
            val loaded = (repository.load(deviceId) as? Outcome.Success)?.value
            if (loaded != null) {
                emit(loaded)
                if (!loaded.pending) return@flow
            }
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 3_000L
        const val DEFAULT_ATTEMPTS = 20
    }
}
