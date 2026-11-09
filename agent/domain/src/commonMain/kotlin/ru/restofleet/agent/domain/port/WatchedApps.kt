package ru.restofleet.agent.domain.port

/**
 * Пакеты отслеживаемых приложений: только их агент имеет право обновлять командой `install_update` (FR-AG-68).
 * Сейчас список статический; задача 3.6 подставит `shadow.watchedApps` без изменений потребителей.
 */
fun interface WatchedApps {
    suspend fun packages(): Set<String>
}
