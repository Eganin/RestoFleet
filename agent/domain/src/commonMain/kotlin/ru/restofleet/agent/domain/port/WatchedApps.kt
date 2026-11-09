package ru.restofleet.agent.domain.port

/**
 * Пакеты отслеживаемых приложений: только их агент имеет право обновлять командой `install_update` (FR-AG-68).
 * Реализация — статический список ∪ `shadow.watchedApps` (3.6b).
 */
fun interface WatchedApps {
    suspend fun packages(): Set<String>
}
