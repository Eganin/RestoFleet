package ru.restofleet.console.feature.auth.domain

import kotlinx.coroutines.flow.Flow
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

/** Вошедший пользователь: сервер и логин. Токены в домен не попадают. */
data class AuthSession(val server: String, val login: String)

/** Порт аутентификации. Реализация — в `auth-data` поверх `SessionManager`. */
interface AuthRepository {
    /** Текущая сессия; `null` — вход не выполнен. */
    val session: Flow<AuthSession?>

    /** Поднимает сессию из хранилища (вход без пароля после перезапуска). */
    suspend fun restore(): AuthSession?

    suspend fun login(server: String, login: String, password: String): Outcome<AuthSession, ConsoleError>

    /** Выход: гасит refresh-токен на сервере (если получится) и стирает локальную сессию. */
    suspend fun logout()
}
