package ru.restofleet.console.core.storage

/** Постоянная часть сессии: что нужно, чтобы войти без пароля. Access-токен сюда не попадает (реш. 420). */
data class StoredSession(val server: String, val login: String, val refreshToken: String) {
    override fun toString(): String = "StoredSession(server=$server, login=$login, refreshToken=***)"
}

/** Порт хранилища сессии. */
interface TokenStore {
    suspend fun load(): StoredSession?

    suspend fun save(session: StoredSession)

    suspend fun clear()
}

/** Хранилище в памяти: Desktop (реш. 422) и тесты. */
class InMemoryTokenStore(private var session: StoredSession? = null) : TokenStore {
    override suspend fun load(): StoredSession? = session

    override suspend fun save(session: StoredSession) {
        this.session = session
    }

    override suspend fun clear() {
        session = null
    }
}
