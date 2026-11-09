package ru.restofleet.agent.data.shadow

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.restofleet.agent.domain.model.isValidPackageName
import ru.restofleet.agent.domain.port.ShadowStore
import ru.restofleet.agent.domain.port.WatchedApps
import kotlin.coroutines.cancellation.CancellationException

/**
 * Отслеживаемые пакеты: статический список ∪ `watchedApps` из сохранённой тени. Пустые и невалидные имена из тени
 * отбрасываются (их не пропускает и `ApplyDesiredShadow`; здесь повторно, на случай старого или испорченного файла).
 *
 * Граница доверия (решение 3.6b): сервер, приславший `desired`, разрешает установку так же, как он шлёт
 * `install_update`; отдельного подписанного allowlist и проверки «пакет уже установлен» в MVP нет.
 */
class ShadowWatchedApps(
    private val static: Set<String>,
    private val store: ShadowStore,
) : WatchedApps {
    override suspend fun packages(): Set<String> = static + fromShadow()

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // сбой хранилища не лишает статического списка
    private suspend fun fromShadow(): Set<String> =
        try {
            store.load()?.watchedApps.orEmpty().filter(::isValidPackageName).toSet()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            emptySet()
        } catch (e: Exception) {
            emptySet()
        }
}
