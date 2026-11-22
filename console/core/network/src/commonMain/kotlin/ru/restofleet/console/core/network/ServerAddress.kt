package ru.restofleet.console.core.network

import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.protocol.rest.console.ConsolePaths

/** Проверка адреса сервера: только `https`, без учётных данных, запроса и фрагмента (реш. 423). */
object ServerAddress {
    private const val SCHEME = "https://"

    fun normalize(raw: String): Outcome<String, ConsoleError.InvalidAddress> {
        val trimmed = raw.trim()
        val authority = trimmed.drop(SCHEME.length).substringBefore('/')
        val reason = when {
            trimmed.isEmpty() -> "empty"
            trimmed.any { it.isWhitespace() } -> "whitespace"
            !trimmed.startsWith(SCHEME, ignoreCase = true) -> "https_required"
            authority.isEmpty() -> "no_host"
            '@' in authority -> "userinfo"
            '?' in trimmed || '#' in trimmed -> "query"
            else -> null
        }
        return if (reason == null) {
            Outcome.Success(SCHEME + trimmed.drop(SCHEME.length).trimEnd('/'))
        } else {
            Outcome.Failure(ConsoleError.InvalidAddress(reason))
        }
    }
}

/** Адрес сервера текущей сессии. Читается на каждый запрос, поэтому смена сервера не требует нового клиента. */
class ServerEndpoint(initial: String? = null) {
    @Volatile
    var baseUrl: String? = initial

    /** Адрес WebSocket: `https` → `wss`. `null`, пока сервер не выбран. */
    fun wsUrl(): String? = baseUrl?.let { "wss://" + it.removePrefix("https://") + ConsolePaths.WS }
}
