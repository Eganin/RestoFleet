package ru.restofleet.console.root.di

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.logging.Logger
import ru.restofleet.console.core.common.AppDispatchers
import ru.restofleet.console.core.storage.TokenStore

/**
 * Всё, что различается между приложениями (Android и Desktop): движок HTTP (доверие к TLS), хранилище токенов,
 * диспетчеры. [debugLogger] задаётся только в debug; заголовок Authorization в журнал не попадает.
 */
class PlatformDependencies(
    val engineFactory: () -> HttpClientEngine,
    val tokenStore: TokenStore,
    val dispatchers: AppDispatchers,
    val debugLogger: Logger? = null,
)
