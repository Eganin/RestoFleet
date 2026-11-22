package ru.restofleet.console.feature.auth.domain

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.shared.kernel.Outcome

class AuthUseCasesTest {
    private class FakeRepo : AuthRepository {
        val state = MutableStateFlow<AuthSession?>(null)
        var loginArgs: List<String>? = null
        var loggedOut = false
        var result: Outcome<AuthSession, ConsoleError> = Outcome.Success(AuthSession("https://s", "u"))
        override val session: Flow<AuthSession?> = state
        override suspend fun restore(): AuthSession? = state.value
        override suspend fun login(
            server: String,
            login: String,
            password: String,
        ): Outcome<AuthSession, ConsoleError> {
            loginArgs = listOf(server, login, password)
            return result
        }
        override suspend fun logout() {
            loggedOut = true
        }
    }

    @Test
    fun `login validation table`() = runTest {
        val rows = listOf(
            Pair(listOf("", "u", "p"), Login.FIELD_SERVER),
            Pair(listOf("  ", "u", "p"), Login.FIELD_SERVER),
            Pair(listOf("https://s", "", "p"), Login.FIELD_LOGIN),
            Pair(listOf("https://s", "   ", "p"), Login.FIELD_LOGIN),
            Pair(listOf("https://s", "u", ""), Login.FIELD_PASSWORD),
        )
        rows.forEach { (args, field) ->
            val repo = FakeRepo()
            val result = Login(repo)(args[0], args[1], args[2])
            assertThat(result).isEqualTo(Outcome.Failure(ConsoleError.InvalidInput(field, "empty")))
            assertThat(repo.loginArgs).isNull()
        }
    }

    @Test
    fun `login trims server and login but never the password`() = runTest {
        val repo = FakeRepo()

        val result = Login(repo)(" https://s ", " ivanova ", " pa ss ")

        assertThat(result).isEqualTo(Outcome.Success(AuthSession("https://s", "u")))
        assertThat(repo.loginArgs).containsExactly("https://s", "ivanova", " pa ss ").inOrder()
    }

    @Test
    fun `login passes repository failure through`() = runTest {
        val repo = FakeRepo().apply { result = Outcome.Failure(ConsoleError.Unauthorized) }
        assertThat(Login(repo)("https://s", "u", "p")).isEqualTo(Outcome.Failure(ConsoleError.Unauthorized))
    }

    @Test
    fun `logout restore and observe delegate to repository`() = runTest {
        val repo = FakeRepo()
        Logout(repo)()
        assertThat(repo.loggedOut).isTrue()
        assertThat(RestoreSession(repo)()).isNull()
        repo.state.value = AuthSession("https://s", "u")
        assertThat(RestoreSession(repo)()).isEqualTo(AuthSession("https://s", "u"))
        ObserveSession(repo)().test { assertThat(awaitItem()).isEqualTo(AuthSession("https://s", "u")) }
    }
}
