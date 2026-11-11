package ru.restofleet.agent.presentation.setup.viewmodel

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.capability.Permission
import ru.restofleet.agent.domain.error.ProvisionError
import ru.restofleet.agent.domain.model.DeviceRole
import ru.restofleet.agent.domain.model.ProvisionedDevice
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.agent.domain.port.ProvisioningApi
import ru.restofleet.agent.domain.usecase.GetPermissions
import ru.restofleet.agent.domain.usecase.Provision
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.Badge
import ru.restofleet.agent.presentation.common.SettingsTarget
import ru.restofleet.agent.presentation.common.StringSpec
import ru.restofleet.agent.presentation.setup.api.ISetupRouter
import ru.restofleet.agent.presentation.setup.viewmodel.SetupIntent.User
import ru.restofleet.agent.presentation.setup.viewmodel.model.DEFAULT_BASE_URL
import ru.restofleet.agent.presentation.setup.viewmodel.model.SetupStep
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome

/** Мастер настройки (план 3.6): разрешения → адрес и код → Provision → результат → статус. */
class SetupViewModelTest {
    private val device = ProvisionedDevice(DeviceId.parse("d-5")!!, "pw", "r-9", DeviceRole.HALL, "10.0.2.2", 8883)

    private inner class Fixture(
        scope: TestScope,
        labAvailable: Boolean = true,
        var granted: () -> Set<Permission> = { setOf(Permission.POST_NOTIFICATIONS) },
        var answer: suspend () -> Outcome<ProvisionedDevice, ProvisionError> = { Outcome.Success(device) },
    ) {
        val navigation = mutableListOf<String>()
        val calls = mutableListOf<Pair<String, String>>()
        val saved = mutableListOf<BrokerSettings>()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val store = object : CredentialStore {
            override suspend fun save(s: BrokerSettings) {
                saved += s
            }

            override suspend fun load(): BrokerSettings? = saved.lastOrNull()
        }
        private val router = object : ISetupRouter {
            override fun openSettings(target: SettingsTarget) {
                navigation += "settings:$target"
            }

            override fun openStatus() {
                navigation += "status"
            }

            override fun openLab() {
                navigation += "lab"
            }
        }
        val vm = SetupViewModel(
            getPermissions = GetPermissions { Capabilities(AgentMode.NORMAL, 36, granted()) },
            provision = Provision(
                ProvisioningApi { url, code ->
                    calls += url to code
                    answer()
                },
                store,
                reset = {},
            ),
            router = router,
            labAvailable = labAvailable,
            io = dispatcher,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        fun ui() = vm.uiState.value
    }

    private fun TestScope.toRegistration(f: Fixture) {
        runCurrent()
        f.vm.onIntent(User.Next)
        f.vm.onIntent(User.EditCode("ABCD-1234"))
    }

    @Test
    fun startsAtPermissionsWithCurrentGrants() = runTest {
        val f = Fixture(this)
        assertThat(f.ui().permissions.map { it.badge }).containsExactly(Badge.NA, Badge.NA, Badge.NA).inOrder()

        runCurrent()

        assertThat(f.ui().step).isEqualTo(SetupStep.PERMISSIONS)
        assertThat(f.ui().stepTitle).isEqualTo(StringSpec(R.string.setup_step_permissions))
        assertThat(f.ui().permissions.map { it.target }).containsExactly(
            SettingsTarget.NOTIFICATIONS, SettingsTarget.USAGE_ACCESS, SettingsTarget.BATTERY_OPTIMIZATION,
        ).inOrder()
        assertThat(f.ui().permissions.map { it.badge }).containsExactly(Badge.OK, Badge.WARN, Badge.WARN).inOrder()
    }

    @Test
    fun returningFromSettingsRereadsPermissions() = runTest {
        val f = Fixture(this)
        runCurrent()
        f.granted = { setOf(Permission.POST_NOTIFICATIONS, Permission.USAGE_ACCESS, Permission.IGNORE_BATTERY_OPT) }

        f.vm.onIntent(User.RefreshPermissions)
        runCurrent()

        assertThat(f.ui().permissions.map { it.badge }).containsExactly(Badge.OK, Badge.OK, Badge.OK)
    }

    @Test
    fun failingPermissionSourceKeepsUnknownState() = runTest {
        val f = Fixture(this, granted = { error("appops") })
        runCurrent()

        assertThat(f.ui().permissions.map { it.badge }).containsExactly(Badge.NA, Badge.NA, Badge.NA)
    }

    @Test
    fun settingsButtonGoesThroughTheRouter() = runTest {
        val f = Fixture(this)

        f.vm.onIntent(User.OpenSettings(SettingsTarget.USAGE_ACCESS))

        assertThat(f.navigation).containsExactly("settings:USAGE_ACCESS")
    }

    @Test
    fun registrationStepHasStandDefaultAndNeedsACode() = runTest {
        val f = Fixture(this)
        runCurrent()

        f.vm.onIntent(User.Next)

        assertThat(f.ui().step).isEqualTo(SetupStep.REGISTRATION)
        assertThat(f.ui().baseUrl).isEqualTo(DEFAULT_BASE_URL)
        assertThat(f.ui().canSubmit).isFalse()
        f.vm.onIntent(User.EditCode("ABCD"))
        assertThat(f.ui().canSubmit).isTrue()
        f.vm.onIntent(User.EditBaseUrl(" "))
        assertThat(f.ui().canSubmit).isFalse()
    }

    @Test
    fun backReturnsToPermissions() = runTest {
        val f = Fixture(this)
        toRegistration(f)

        f.vm.onIntent(User.Back)

        assertThat(f.ui().step).isEqualTo(SetupStep.PERMISSIONS)
    }

    @Test
    fun successfulRegistrationShowsDeviceRoleAndRestaurantThenGoesToStatus() = runTest {
        val f = Fixture(this)
        toRegistration(f)

        f.vm.onIntent(User.Submit)
        assertThat(f.ui().isSubmitting).isTrue()
        runCurrent()

        assertThat(f.calls).containsExactly(DEFAULT_BASE_URL to "ABCD-1234")
        assertThat(f.saved.single().role).isEqualTo(DeviceRole.HALL)
        assertThat(f.ui().step).isEqualTo(SetupStep.DONE)
        assertThat(f.ui().code).isEmpty()
        assertThat(f.ui().result.map { it.value.args })
            .containsExactly(listOf("d-5"), emptyList<String>(), listOf("r-9"))
        assertThat(f.ui().result[1].value).isEqualTo(StringSpec(R.string.role_hall))

        f.vm.onIntent(User.Finish)
        assertThat(f.navigation).containsExactly("status")
    }

    @Test
    fun refusalIsShownAndEditingTheCodeClearsIt() = runTest {
        val f = Fixture(this, answer = { Outcome.Failure(ProvisionError.CodeUsed) })
        toRegistration(f)

        f.vm.onIntent(User.Submit)
        runCurrent()

        assertThat(f.ui().step).isEqualTo(SetupStep.REGISTRATION)
        assertThat(f.ui().error).isEqualTo(StringSpec(R.string.setup_error_code_used))
        assertThat(f.saved).isEmpty()
        f.vm.onIntent(User.EditCode("NEW"))
        assertThat(f.ui().error).isNull()
    }

    @Test
    fun secondSubmitWhileRegisteringIsIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this, answer = {
            gate.await()
            Outcome.Success(device)
        })
        toRegistration(f)

        f.vm.onIntent(User.Submit)
        runCurrent()
        f.vm.onIntent(User.Submit)
        f.vm.onIntent(User.Back)
        gate.complete(Unit)
        runCurrent()

        assertThat(f.calls).hasSize(1)
        assertThat(f.ui().step).isEqualTo(SetupStep.DONE)
    }

    @Test
    fun throwingProvisionBecomesAFailureMessage() = runTest {
        val f = Fixture(this, answer = { throw IllegalStateException("boom") })
        toRegistration(f)

        f.vm.onIntent(User.Submit)
        runCurrent()

        assertThat(f.ui().isSubmitting).isFalse()
        assertThat(f.ui().error).isEqualTo(StringSpec(R.string.setup_error_failed, listOf("IllegalStateException")))
    }

    @Test
    fun finishBeforeDoneAndNextOutsidePermissionsDoNothing() = runTest {
        val f = Fixture(this)
        runCurrent()

        f.vm.onIntent(User.Finish)
        f.vm.onIntent(User.Back)
        f.vm.onIntent(User.Submit)

        assertThat(f.navigation).isEmpty()
        assertThat(f.ui().step).isEqualTo(SetupStep.PERMISSIONS)
        assertThat(f.calls).isEmpty()
    }

    @Test
    fun labIsOpenedOnlyWhenAvailable() = runTest {
        val debug = Fixture(this)
        val release = Fixture(this, labAvailable = false)

        debug.vm.onIntent(User.OpenLab)
        release.vm.onIntent(User.OpenLab)

        assertThat(debug.navigation).containsExactly("lab")
        assertThat(release.navigation).isEmpty()
        assertThat(release.ui().labAvailable).isFalse()
    }
}
