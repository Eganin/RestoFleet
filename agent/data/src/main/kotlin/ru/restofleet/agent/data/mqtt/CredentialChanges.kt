package ru.restofleet.agent.data.mqtt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore

/**
 * Счётчик смен учётки брокера. Сигнал внутренний для data/app (в порт `CredentialStore` не входит, решение 23):
 * работающая сессия читает [current] до загрузки учётки и ждёт [awaitChangeFrom], поэтому смена между чтением и
 * ожиданием не теряется (в отличие от `SharedFlow` без повтора).
 */
class CredentialChanges {
    private val version = MutableStateFlow(0L)

    fun current(): Long = version.value

    fun notifyChanged() {
        version.value += 1
    }

    suspend fun awaitChangeFrom(seen: Long) {
        version.first { it != seen }
    }

    /** Ждёт смену не дольше [timeoutMs] (пауза между попытками подключения, её прерывает новая учётка). */
    suspend fun awaitChangeOrTimeout(seen: Long, timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { awaitChangeFrom(seen) }
    }
}

/** Оборачивает хранилище: после успешной записи сообщает сессии о смене учётки. */
class NotifyingCredentialStore(
    private val delegate: CredentialStore,
    private val changes: CredentialChanges,
) : CredentialStore {
    override suspend fun save(s: BrokerSettings) {
        delegate.save(s)
        changes.notifyChanged()
    }

    override suspend fun load(): BrokerSettings? = delegate.load()
}
