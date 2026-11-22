package ru.restofleet.agent.presentation.root.viewmodel

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.model.DeviceRole
import ru.restofleet.agent.domain.model.Registration
import ru.restofleet.agent.domain.model.RegistrationState
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.agent.domain.usecase.GetRegistration
import ru.restofleet.agent.presentation.root.viewmodel.RootIntent.External
import ru.restofleet.agent.presentation.root.viewmodel.RootIntent.User
import ru.restofleet.agent.presentation.root.viewmodel.model.AgentScreen
import ru.restofleet.agent.presentation.root.viewmodel.model.RootSession
import ru.restofleet.shared.kernel.DeviceId
import java.io.IOException

/** План 3.6: «нет учётки → мастер, есть → статус»; Lab — только debug, «назад» возвращает туда, откуда открыли. */
class RootViewModelTest {
    private val id = DeviceId.parse("d-1")!!

    private fun TestScope.viewModel(labAvailable: Boolean = true, load: suspend () -> BrokerSettings?): RootViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = object : CredentialStore {
            override suspend fun save(s: BrokerSettings) = Unit

            override suspend fun load(): BrokerSettings? = load()
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return RootViewModel(GetRegistration(store), labAvailable, dispatcher, scope)
    }

    @Test
    fun loadingUntilRegistrationIsRead() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel {
            gate.await()
            null
        }
        runCurrent()
        assertThat(vm.uiState.value.screen).isEqualTo(AgentScreen.LOADING)

        gate.complete(Unit)
        runCurrent()
        assertThat(vm.uiState.value.screen).isEqualTo(AgentScreen.SETUP)
    }

    @Test
    fun withoutCredentialsTheWizardIsShown() = runTest {
        val vm = viewModel { null }
        runCurrent()

        assertThat(vm.uiState.value.screen).isEqualTo(AgentScreen.SETUP)
    }

    @Test
    fun withCredentialsTheStatusIsShown() = runTest {
        val vm = viewModel { BrokerSettings("h", 8883, id, "pw", DeviceRole.HALL, "r") }
        runCurrent()

        assertThat(vm.uiState.value.screen).isEqualTo(AgentScreen.STATUS)
    }

    @Test
    fun unreadableKeystoreShowsStatusNotTheWizard() = runTest {
        val vm = viewModel { throw IOException("keystore") }
        runCurrent()

        assertThat(vm.uiState.value.screen).isEqualTo(AgentScreen.STATUS)
    }

    @Test
    fun completedSetupLeadsToStatus() = runTest {
        val vm = viewModel { null }
        runCurrent()

        vm.onIntent(External.SetupCompleted)

        assertThat(vm.uiState.value.screen).isEqualTo(AgentScreen.STATUS)
    }

    @Test
    fun labOpensFromStatusAndBackReturnsThere() = runTest {
        val vm = viewModel { BrokerSettings("h", 8883, id, "pw") }
        runCurrent()

        vm.onIntent(User.OpenLab)
        assertThat(vm.uiState.value.screen).isEqualTo(AgentScreen.LAB)
        assertThat(vm.uiState.value.handlesBack).isTrue()

        vm.onIntent(User.Back)
        assertThat(vm.uiState.value.screen).isEqualTo(AgentScreen.STATUS)
        assertThat(vm.uiState.value.handlesBack).isFalse()
    }

    @Test
    fun labOpenedFromTheWizardReturnsToTheWizard() = runTest {
        val vm = viewModel { null }
        runCurrent()

        vm.onIntent(User.OpenLab)
        vm.onIntent(User.Back)

        assertThat(vm.uiState.value.screen).isEqualTo(AgentScreen.SETUP)
    }

    @Test
    fun labIsUnreachableWhenUnavailable() = runTest {
        val vm = viewModel(labAvailable = false) { BrokerSettings("h", 8883, id, "pw") }
        runCurrent()

        vm.onIntent(User.OpenLab)

        assertThat(vm.uiState.value.screen).isEqualTo(AgentScreen.STATUS)
        assertThat(vm.uiState.value.labAvailable).isFalse()
    }

    @Test
    fun reducerIgnoresTransitionsFromWrongScreens() {
        val reducer = RootReducer()
        val loading = RootSession(labAvailable = true)
        val status = RootSession(screen = AgentScreen.STATUS, labAvailable = true)
        val registered = RegistrationState.Registered(Registration(id, null, null, "h", 1))

        assertThat(reducer.reduce(RootAction.LabOpened, loading)).isSameInstanceAs(loading)
        assertThat(reducer.reduce(RootAction.LabClosed, status)).isSameInstanceAs(status)
        assertThat(reducer.reduce(RootAction.SetupCompleted, status)).isSameInstanceAs(status)
        assertThat(reducer.reduce(RootAction.RegistrationResolved(RegistrationState.NotRegistered), status))
            .isSameInstanceAs(status)
        assertThat(reducer.reduce(RootAction.RegistrationResolved(registered), loading).screen)
            .isEqualTo(AgentScreen.STATUS)
        assertThat(reducer.reduce(RootAction.LabClosed, RootSession(screen = AgentScreen.LAB)).screen)
            .isEqualTo(AgentScreen.STATUS)
    }
}
