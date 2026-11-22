package ru.restofleet.console.feature.auth.ui.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.feature.auth.domain.AuthSession
import ru.restofleet.console.feature.auth.domain.Login
import ru.restofleet.console.feature.auth.ui.api.IAuthRouter
import ru.restofleet.console.feature.auth.ui.viewmodel.mapper.LoginUiStateMapper
import ru.restofleet.console.feature.auth.ui.viewmodel.model.LoginSession
import ru.restofleet.shared.kernel.Outcome

class LoginTest {
    private val reducer = LoginReducer()
    private val mapper = LoginUiStateMapper()

    @Test
    fun `reducer table`() {
        val filled = LoginSession("https://s", "u", "p", error = ConsoleError.Unauthorized)
        assertThat(reducer.reduce(LoginAction.ServerChanged("x"), filled))
            .isEqualTo(filled.copy(server = "x", error = null))
        assertThat(reducer.reduce(LoginAction.LoginChanged("x"), filled)).isEqualTo(filled.copy(login = "x", error = null))
        assertThat(reducer.reduce(LoginAction.PasswordChanged("x"), filled))
            .isEqualTo(filled.copy(password = "x", error = null))
        assertThat(reducer.reduce(LoginAction.SubmitStarted, filled)).isEqualTo(filled.copy(submitting = true, error = null))
        val busy = filled.copy(submitting = true, error = null)
        assertThat(reducer.reduce(LoginAction.SubmitFailed(ConsoleError.Network), busy))
            .isEqualTo(busy.copy(submitting = false, error = ConsoleError.Network))
        assertThat(reducer.reduce(LoginAction.SubmitSucceeded, busy)).isEqualTo(busy.copy(submitting = false, password = ""))
    }

    @Test
    fun `fields are frozen while submitting`() {
        val busy = LoginSession("a", "b", "c", submitting = true)
        listOf(LoginAction.ServerChanged("x"), LoginAction.LoginChanged("x"), LoginAction.PasswordChanged("x"))
            .forEach { assertThat(reducer.reduce(it, busy)).isSameInstanceAs(busy) }
    }

    @Test
    fun `session hides the password in toString`() {
        assertThat(LoginSession(password = "hunter2").toString()).doesNotContain("hunter2")
    }

    @Test
    fun `mapper table`() {
        val ready = LoginSession("https://s", "u", "p")
        assertThat(mapper.map(ready).submitEnabled).isTrue()
        assertThat(mapper.map(ready.copy(password = "")).submitEnabled).isFalse()
        assertThat(mapper.map(ready.copy(login = " ")).submitEnabled).isFalse()
        assertThat(mapper.map(ready.copy(server = "")).submitEnabled).isFalse()
        assertThat(mapper.map(ready.copy(submitting = true)).submitEnabled).isFalse()
        assertThat(mapper.map(ready.copy(submitting = true)).loading).isTrue()

        fun form(e: ConsoleError) = mapper.map(ready.copy(error = e)).formError
        assertThat(form(ConsoleError.Unauthorized)).isEqualTo("Неверный логин или пароль")
        assertThat(form(ConsoleError.Network)).isEqualTo("Нет связи с сервером")
        assertThat(form(ConsoleError.RateLimited(3))).contains("Слишком много")
        assertThat(form(ConsoleError.Server(503, null))).isEqualTo("Ошибка сервера (503)")
        assertThat(form(ConsoleError.Forbidden)).isEqualTo("Доступ запрещён")
        assertThat(form(ConsoleError.Unexpected("x"))).isEqualTo("Не удалось войти")
        assertThat(mapper.map(ready).formError).isNull()
    }

    @Test
    fun `mapper binds field errors`() {
        val base = LoginSession()
        fun ui(e: ConsoleError) = mapper.map(base.copy(error = e))
        assertThat(ui(ConsoleError.InvalidAddress("https_required")).serverError).isEqualTo("Нужен адрес https://")
        assertThat(ui(ConsoleError.InvalidAddress("userinfo")).serverError).contains("Уберите")
        assertThat(ui(ConsoleError.InvalidAddress("empty")).serverError).isEqualTo("Неверный адрес сервера")
        assertThat(ui(ConsoleError.InvalidInput(Login.FIELD_SERVER, "empty")).serverError).isEqualTo("Укажите адрес сервера")
        assertThat(ui(ConsoleError.InvalidInput(Login.FIELD_LOGIN, "empty")).loginError).isEqualTo("Укажите логин")
        assertThat(ui(ConsoleError.InvalidInput(Login.FIELD_PASSWORD, "empty")).passwordError).isEqualTo("Введите пароль")
        assertThat(ui(ConsoleError.InvalidInput(Login.FIELD_PASSWORD, "empty")).formError).isNull()
    }

    private fun vm(result: Outcome<AuthSession, ConsoleError>, router: IAuthRouter = mockk(relaxed = true)): Pair<LoginViewModel, Login> {
        val login = mockk<Login>()
        coEvery { login(any(), any(), any()) } returns result
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        return LoginViewModel(login, router, scope) to login
    }

    @Test
    fun `submit success calls router, hides keyboard and clears the password`() = runTest {
        val router = mockk<IAuthRouter>(relaxed = true)
        val (vm, login) = vm(Outcome.Success(AuthSession("https://s", "u")), router)
        vm.onIntent(LoginIntent.User.ServerChanged("https://s"))
        vm.onIntent(LoginIntent.User.LoginChanged("u"))
        vm.onIntent(LoginIntent.User.PasswordChanged("p"))

        vm.effect.test {
            vm.onIntent(LoginIntent.User.Submit)
            assertThat(awaitItem()).isEqualTo(LoginEffect.HideKeyboard)
        }

        coVerify { login("https://s", "u", "p") }
        verify { router.onLoggedIn() }
        assertThat(vm.uiState.value.password).isEmpty()
        assertThat(vm.uiState.value.loading).isFalse()
    }

    @Test
    fun `submit failure shows the error and does not navigate`() = runTest {
        val router = mockk<IAuthRouter>(relaxed = true)
        val (vm, _) = vm(Outcome.Failure(ConsoleError.Unauthorized), router)
        vm.onIntent(LoginIntent.User.PasswordChanged("p"))

        vm.onIntent(LoginIntent.User.Submit)

        assertThat(vm.uiState.value.formError).isEqualTo("Неверный логин или пароль")
        assertThat(vm.uiState.value.loading).isFalse()
        verify(exactly = 0) { router.onLoggedIn() }
    }
}
