package ru.restofleet.agent.domain.usecase

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.restofleet.agent.domain.model.AgentEvent
import ru.restofleet.agent.domain.model.AgentEvent.PackageChanged
import ru.restofleet.shared.kernel.Clock

/**
 * Событие `package_changed` с дедупликацией (реш. 204): на одно обновление или удаление система может прислать два
 * одинаковых сообщения, поэтому `(pkg, change, versionCode)`, уже отправленное меньше [windowMs] назад, отбрасывается.
 * Окно отсчитывается от последней отправки, а не от отброшенного дубля. Часы, ушедшие назад, окно не продлевают:
 * отрицательный интервал считается выходом из окна. Состояние — в памяти: после перезапуска агента дубля быть не может.
 */
class PublishPackageChange(
    private val publishEvent: PublishEvent,
    private val clock: Clock,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    private val mutex = Mutex()

    /** Ключ — `(pkg, change, versionCode)`; Triple, а не свой класс: в пакете `usecase` живут только use cases. */
    private val lastSent = mutableMapOf<Triple<String, PackageChanged.Change, Long?>, Long>()

    suspend operator fun invoke(pkg: String, change: PackageChanged.Change, versionCode: Long?) {
        val now = clock.nowMillis()
        val key = Triple(pkg, change, versionCode)
        val fresh = mutex.withLock {
            lastSent.entries.removeAll { (_, at) -> !within(at, now) }
            if (key in lastSent) {
                false
            } else {
                lastSent[key] = now
                true
            }
        }
        if (fresh) publishEvent(AgentEvent.PackageChanged(now, pkg, change, versionCode))
    }

    private fun within(at: Long, now: Long): Boolean = now - at in 0 until windowMs

    private companion object {
        const val DEFAULT_WINDOW_MS = 5_000L
    }
}
