package ru.restofleet.console.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.restofleet.console.core.storage.StoredSession
import ru.restofleet.console.core.storage.TokenStore
import ru.restofleet.shared.protocol.rest.console.TokenResponseDto

/** Состояние входа для навигации. */
sealed interface SessionState {
    data object LoggedOut : SessionState

    data class LoggedIn(val server: String, val login: String) : SessionState
}

/** Итог запроса `refresh`. */
sealed interface RefreshResult {
    data class Ok(val tokens: TokenResponseDto) : RefreshResult

    /** Сервер отверг refresh-токен: сессия кончилась. */
    data object Rejected : RefreshResult

    /** Нет связи или 5xx: сессию не трогаем. */
    data object Unavailable : RefreshResult
}

fun interface RefreshApi {
    suspend fun refresh(refreshToken: String): RefreshResult
}

/** Итог обновления токена для тех, кто уже получил 401. */
sealed interface RefreshOutcome {
    data class Refreshed(val accessToken: String) : RefreshOutcome

    data object SessionEnded : RefreshOutcome

    data object Unavailable : RefreshOutcome
}

/** Источник access-токена: клиенты HTTP и WebSocket не знают, откуда он берётся. */
interface AccessTokenSource {
    fun accessToken(): String?

    /** [failed] — токен, с которым получили 401. Если токен уже обновили, возвращается текущий без запроса. */
    suspend fun refresh(failed: String?): RefreshOutcome
}

/**
 * Сессия консоли. Access-токен — только в памяти, refresh — в [TokenStore]. Обновление single-flight под Mutex:
 * параллельные 401 приводят к одному `refresh`, новая пара сохраняется до повторного запроса (refresh одноразовый),
 * отказ сервера (401) завершает сессию.
 */
class SessionManager(
    private val store: TokenStore,
    private val refreshApi: RefreshApi,
    private val endpoint: ServerEndpoint,
) : AccessTokenSource {
    private val mutex = Mutex()
    @Volatile
    private var access: String? = null
    private var refreshToken: String? = null
    private var identity: Pair<String, String>? = null
    private val _state = MutableStateFlow<SessionState>(SessionState.LoggedOut)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Поднимает сессию из хранилища; access-токена нет — первый запрос получит 401 и обновит его. */
    suspend fun restore(): SessionState = mutex.withLock {
        val stored = store.load()
        if (stored != null) {
            endpoint.baseUrl = stored.server
            refreshToken = stored.refreshToken
            identity = stored.server to stored.login
            _state.value = SessionState.LoggedIn(stored.server, stored.login)
        }
        _state.value
    }

    suspend fun establish(server: String, login: String, tokens: TokenResponseDto) = mutex.withLock {
        store.save(StoredSession(server, login, tokens.refreshToken))
        endpoint.baseUrl = server
        access = tokens.accessToken
        refreshToken = tokens.refreshToken
        identity = server to login
        _state.value = SessionState.LoggedIn(server, login)
    }

    override fun accessToken(): String? = access

    suspend fun currentRefreshToken(): String? = mutex.withLock { refreshToken }

    override suspend fun refresh(failed: String?): RefreshOutcome = mutex.withLock {
        val current = access
        val saved = refreshToken
        when {
            current != null && current != failed -> RefreshOutcome.Refreshed(current)
            saved == null -> RefreshOutcome.SessionEnded
            else -> applyRefresh(refreshApi.refresh(saved))
        }
    }

    private suspend fun applyRefresh(result: RefreshResult): RefreshOutcome = when (result) {
        is RefreshResult.Ok -> {
            val (server, login) = checkNotNull(identity)
            store.save(StoredSession(server, login, result.tokens.refreshToken))
            access = result.tokens.accessToken
            refreshToken = result.tokens.refreshToken
            RefreshOutcome.Refreshed(result.tokens.accessToken)
        }
        RefreshResult.Rejected -> {
            clearLocked()
            RefreshOutcome.SessionEnded
        }
        RefreshResult.Unavailable -> RefreshOutcome.Unavailable
    }

    /** Локальный выход (после запроса `logout` или при отказе refresh). */
    suspend fun end() = mutex.withLock { clearLocked() }

    private suspend fun clearLocked() {
        store.clear()
        access = null
        refreshToken = null
        identity = null
        _state.value = SessionState.LoggedOut
    }
}
