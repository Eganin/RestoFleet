package ru.restofleet.console.feature.provisioning.ui.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.console.core.common.ConsoleError
import ru.restofleet.console.feature.provisioning.domain.DeviceRole
import ru.restofleet.console.feature.provisioning.domain.IssueCode
import ru.restofleet.console.feature.provisioning.domain.IssuedCode
import ru.restofleet.console.feature.provisioning.domain.LoadRestaurantChoices
import ru.restofleet.console.feature.provisioning.domain.ObserveCodeCountdown
import ru.restofleet.console.feature.provisioning.domain.ProvisioningRepository
import ru.restofleet.console.feature.provisioning.domain.ProvisioningRequest
import ru.restofleet.console.feature.provisioning.domain.RestaurantChoice
import ru.restofleet.console.feature.provisioning.ui.api.IProvisioningRouter
import ru.restofleet.console.feature.provisioning.ui.viewmodel.mapper.ProvisioningUiStateMapper
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome

class ProvisioningTest {
    private val sokol = RestaurantChoice("r1", "Сокол · MSK-01")
    private val himki = RestaurantChoice("r2", "Химки · MSK-02")
    private val code = IssuedCode("t1", "ABCD1234EFGH5678", "r1", DeviceRole.Kitchen, "Кухня 3", 86_400_000L)
    private val reducer = ProvisioningReducer()
    private val mapper = ProvisioningUiStateMapper()

    @Test
    fun `reducer preselects a single restaurant and keeps the choice otherwise`() {
        assertThat(reducer.reduce(ProvisioningAction.RestaurantsLoaded(listOf(sokol)), ProvisioningSession()).restaurantId).isEqualTo("r1")
        assertThat(reducer.reduce(ProvisioningAction.RestaurantsLoaded(listOf(sokol, himki)), ProvisioningSession()).restaurantId).isNull()
        assertThat(reducer.reduce(ProvisioningAction.RestaurantsLoaded(listOf(sokol, himki)), ProvisioningSession(restaurantId = "r2")).restaurantId).isEqualTo("r2")
    }

    @Test
    fun `reducer selection, issue, countdown and reset`() {
        val s = ProvisioningSession(restaurantId = "r1", name = "x")
        assertThat(reducer.reduce(ProvisioningAction.RestaurantSelected("r2"), s).restaurantId).isEqualTo("r2")
        assertThat(reducer.reduce(ProvisioningAction.RoleSelected(DeviceRole.Hall), s).role).isEqualTo(DeviceRole.Hall)
        assertThat(reducer.reduce(ProvisioningAction.NameChanged("Зал"), s).name).isEqualTo("Зал")
        assertThat(reducer.reduce(ProvisioningAction.IssueStarted, s).issuing).isTrue()
        assertThat(reducer.reduce(ProvisioningAction.IssueFailed(ConsoleError.Network), s.copy(issuing = true)).issuing).isFalse()
        val issued = reducer.reduce(ProvisioningAction.Issued(code, 5_000), s.copy(issuing = true))
        assertThat(issued.issued).isEqualTo(code)
        assertThat(issued.remainingMs).isEqualTo(5_000)
        assertThat(reducer.reduce(ProvisioningAction.Tick(4_000), issued).remainingMs).isEqualTo(4_000)
        val expired = reducer.reduce(ProvisioningAction.Tick(0), issued)
        assertThat(expired.issued).isNull()
        assertThat(expired.expired).isTrue()
        val reset = reducer.reduce(ProvisioningAction.Reset, issued)
        assertThat(reset.issued).isNull()
        assertThat(reset.name).isEmpty()
        assertThat(reset.restaurantId).isEqualTo("r1")
    }

    @Test
    fun `mapper form state`() {
        val ui = mapper.map(ProvisioningSession(restaurants = listOf(sokol, himki), restaurantId = "r1"))
        assertThat(ui.restaurants.map { it.selected }).containsExactly(true, false).inOrder()
        assertThat(ui.roles.map { it.label }).containsExactly("Кухня", "Зал", "Сборка").inOrder()
        assertThat(ui.roles.first { it.selected }.value).isEqualTo("kitchen")
        assertThat(ui.canIssue).isTrue()
        assertThat(mapper.map(ProvisioningSession()).canIssue).isFalse()
        assertThat(mapper.map(ProvisioningSession(restaurantId = "r1", role = null)).canIssue).isFalse()
        assertThat(mapper.map(ProvisioningSession(restaurantId = "r1", issuing = true)).canIssue).isFalse()
        assertThat(ui.code).isNull()
        assertThat(ui.adbHint).isNull()
    }

    @Test
    fun `mapper shows the grouped code, countdown, adb hint and expiry`() {
        val ui = mapper.map(ProvisioningSession(restaurantId = "r1", issued = code, remainingMs = 86_352_000L))
        assertThat(ui.code).isEqualTo("ABCD 1234 EFGH 5678")
        assertThat(ui.countdown).isEqualTo("действует 23:59:12 · одноразовый")
        assertThat(ui.adbHint).isEqualTo(
            "adb shell am broadcast -a ru.restofleet.agent.DEV_PROVISION -n ru.restofleet.agent/.DevConfigReceiver --es code ABCD1234EFGH5678",
        )
        assertThat(ui.canIssue).isFalse()
        assertThat(mapper.map(ProvisioningSession(expired = true)).expiredText).isEqualTo("Код истёк. Выпустите новый.")
        assertThat(mapper.map(ProvisioningSession(issued = code)).expiredText).isNull()
    }

    @Test
    fun `mapper errors`() {
        fun err(e: ConsoleError) = mapper.map(ProvisioningSession(error = e)).errorText
        assertThat(err(ConsoleError.Network)).isEqualTo("Нет связи с сервером")
        assertThat(err(ConsoleError.Forbidden)).isEqualTo("Недостаточно прав")
        assertThat(err(ConsoleError.RateLimited(5))).contains("Слишком много")
        assertThat(err(ConsoleError.Validation("x", "bad"))).contains("bad")
        assertThat(err(ConsoleError.InvalidInput("restaurant", "empty"))).isEqualTo("Выберите ресторан и роль")
        assertThat(err(ConsoleError.Server(500, null))).isEqualTo("Не удалось выпустить код")
        val name = mapper.map(ProvisioningSession(error = ConsoleError.InvalidInput("name", "too_long")))
        assertThat(name.nameError).isEqualTo("Имя длиннее 100 символов")
        assertThat(name.errorText).isNull()
        assertThat(mapper.map(ProvisioningSession()).nameError).isNull()
    }

    private class Repo : ProvisioningRepository {
        var request: ProvisioningRequest? = null
        var result: Outcome<IssuedCode, ConsoleError>? = null
        override suspend fun restaurants() = Outcome.Success(listOf(RestaurantChoice("r1", "Сокол · MSK-01")))
        override suspend fun issue(request: ProvisioningRequest): Outcome<IssuedCode, ConsoleError> {
            this.request = request
            return result ?: Outcome.Success(IssuedCode("t1", "ABCD1234EFGH5678", request.restaurantId, request.role, request.name, 3_000L))
        }
    }

    private fun TestScope.vm(repo: Repo, router: IProvisioningRouter = mockk(relaxed = true)) = ProvisioningViewModel(
        LoadRestaurantChoices(repo), IssueCode(repo), ObserveCodeCountdown(Clock { currentTime }), router,
        CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
    )

    @Test
    fun `issues a code for the preselected restaurant and role, counts down and erases it at expiry`() = runTest {
        val repo = Repo()
        val vm = vm(repo)
        vm.onIntent(ProvisioningIntent.User.NameChanged("Кухня 3"))

        vm.onIntent(ProvisioningIntent.User.Issue)

        assertThat(repo.request).isEqualTo(ProvisioningRequest("r1", DeviceRole.Kitchen, "Кухня 3"))
        assertThat(vm.uiState.value.code).isEqualTo("ABCD 1234 EFGH 5678")
        assertThat(vm.uiState.value.countdown).isEqualTo("действует 00:00:03 · одноразовый")
        advanceTimeBy(1_001)
        assertThat(vm.uiState.value.countdown).isEqualTo("действует 00:00:02 · одноразовый")
        advanceTimeBy(2_500)
        assertThat(vm.uiState.value.code).isNull()
        assertThat(vm.uiState.value.expiredText).isNotNull()
        vm.onDestroy()
    }

    @Test
    fun `copy emits the raw code to the platform and a second issue is ignored while a code is shown`() = runTest {
        val repo = Repo()
        val vm = vm(repo)
        vm.onIntent(ProvisioningIntent.User.Copy)
        vm.effect.test {
            vm.onIntent(ProvisioningIntent.User.Issue)
            vm.onIntent(ProvisioningIntent.User.Copy)
            assertThat(awaitItem()).isEqualTo(ProvisioningEffect.CopyToClipboard("ABCD1234EFGH5678"))
            cancelAndIgnoreRemainingEvents()
        }
        repo.request = null
        vm.onIntent(ProvisioningIntent.User.Issue)
        assertThat(repo.request).isNull()
        vm.onDestroy()
    }

    @Test
    fun `new code erases the shown one, failures are shown`() = runTest {
        val repo = Repo()
        val vm = vm(repo)
        vm.onIntent(ProvisioningIntent.User.Issue)
        vm.onIntent(ProvisioningIntent.User.NewCode)
        assertThat(vm.uiState.value.code).isNull()
        assertThat(vm.uiState.value.countdown).isNull()

        repo.result = Outcome.Failure(ConsoleError.RateLimited(60))
        vm.onIntent(ProvisioningIntent.User.Issue)
        assertThat(vm.uiState.value.errorText).contains("Слишком много")
        assertThat(vm.uiState.value.issuing).isFalse()
        vm.onDestroy()
    }

    @Test
    fun `selection intents, role and restaurant are validated, back goes to the router`() = runTest {
        val repo = Repo()
        val router = mockk<IProvisioningRouter>(relaxed = true)
        val vm = vm(repo, router)
        vm.onIntent(ProvisioningIntent.User.SelectRestaurant("r1"))
        vm.onIntent(ProvisioningIntent.User.SelectRole(DeviceRole.Assembly))
        vm.onIntent(ProvisioningIntent.User.NameChanged("x".repeat(101)))
        vm.onIntent(ProvisioningIntent.User.Issue)
        assertThat(vm.uiState.value.nameError).isNotNull()
        assertThat(repo.request).isNull()
        vm.onIntent(ProvisioningIntent.User.Back)
        verify { router.back() }
        vm.onDestroy()
    }
}
