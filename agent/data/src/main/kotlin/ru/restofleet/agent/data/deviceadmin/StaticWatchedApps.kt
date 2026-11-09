package ru.restofleet.agent.data.deviceadmin

import ru.restofleet.agent.domain.port.WatchedApps

/** Фиксированный список отслеживаемых пакетов (M1: `CapabilityChecksFactory.DEFAULT_WATCHED`). */
class StaticWatchedApps(private val packages: Set<String>) : WatchedApps {
    constructor(packages: List<String>) : this(packages.toSet())

    override suspend fun packages(): Set<String> = packages
}
