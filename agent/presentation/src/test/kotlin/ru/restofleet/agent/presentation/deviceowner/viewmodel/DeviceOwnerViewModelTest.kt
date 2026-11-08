package ru.restofleet.agent.presentation.deviceowner.viewmodel

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.domain.usecase.FetchApkChecksum
import ru.restofleet.agent.domain.usecase.GetDeviceInfo
import ru.restofleet.agent.domain.usecase.GrantSelfPermissions
import ru.restofleet.agent.domain.usecase.RunCapabilityChecks
import ru.restofleet.agent.domain.usecase.RunCommandCheck
import ru.restofleet.agent.presentation.common.Badge
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerEffect.ConfirmReboot
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerEffect.ShowMessage
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerIntent.User
import ru.restofleet.agent.presentation.support.CommandPorts
import ru.restofleet.agent.presentation.support.MemoryJournal
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DomainError
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.agent.presentation.support.Code

class DeviceOwnerViewModelTest {
    private val clock = Clock { 1L }
    private val sha = "ab".repeat(32)
    private val apk = "https://10.0.2.2:8443/waiter-4.13.apk"

    private inner class Fixture(
        scope: TestScope,
        mode: AgentMode = AgentMode.DEVICE_OWNER,
        var fetchAnswer: () -> Outcome<String, DomainError> = { Outcome.Success(sha) },
        private val fetchGate: CompletableDeferred<Unit>? = null,
        var deviceMode: () -> AgentMode = { mode },
    ) {
        val events = mutableListOf<String>()
        val ports = CommandPorts(mode, events)
        val journal = MemoryJournal()
        val fetched = mutableListOf<String>()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val checks = listOf(CheckId.D1, CheckId.R10, CheckId.R13).map { id ->
            object : CapabilityCheck {
                override val id = id
                override suspend fun run(): CheckResult {
                    events += "check:${id.name}"
                    return CheckResult(id, CheckStatus.OK, mapOf("k" to "v"), null, 1L)
                }
            }
        }
        val vm = DeviceOwnerViewModel(
            runChecks = RunCapabilityChecks(checks, clock),
            runCommand = RunCommandCheck(ports.executeCommand(clock), journal, clock),
            grantPermissions = GrantSelfPermissions(ports.capabilities, ports.admin, journal, clock),
            fetchChecksum = FetchApkChecksum { url ->
                fetched += url
                fetchGate?.await()
                fetchAnswer()
            },
            getDeviceInfo = GetDeviceInfo { DeviceInfo(36, "Pixel", true, deviceMode(), "0.1") },
            clock = clock,
            io = dispatcher,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )

        fun row(id: String) = vm.uiState.value.rows.first { it.id == id }

        fun setUrlAndHash() {
            vm.onIntent(User.EditApkUrl(apk))
            vm.onIntent(User.EditApkSha256(sha))
        }

        fun commands() = events.filterNot { it.startsWith("check:") }
    }

    @Test
    fun openingTheScreenLoadsTheModeAndReadsD1R10R13() = runTest {
        val f = Fixture(this)

        advanceUntilIdle()

        assertThat(f.vm.uiState.value.title).isEqualTo("Device Owner активен")
        assertThat(f.events).containsExactly("check:D1", "check:R10", "check:R13").inOrder()
        assertThat(f.row("D1").badge).isEqualTo(Badge.OK)
        assertThat(f.row("R13").badge).isEqualTo(Badge.OK)
        assertThat(f.row("D1").isRunning).isFalse()
    }

    @Test
    fun withoutDeviceOwnerTheTitleIsNormalModeAndTheRowsAreNotApplicable() = runTest {
        val f = Fixture(this, AgentMode.NORMAL)

        advanceUntilIdle()

        assertThat(f.vm.uiState.value.title).isEqualTo("Обычный режим")
        assertThat(f.row("D2").badge).isEqualTo(Badge.NA)
        assertThat(f.row("R10").summary).isEqualTo("только в режиме Device Owner")
    }

    @Test
    fun refreshPicksUpARoleAssignedWhileTheScreenIsOpen() = runTest {
        val f = Fixture(this, deviceMode = { AgentMode.NORMAL })
        advanceUntilIdle()
        assertThat(f.vm.uiState.value.title).isEqualTo("Обычный режим")

        f.deviceMode = { AgentMode.DEVICE_OWNER }
        f.vm.onIntent(User.Refresh)
        advanceUntilIdle()

        assertThat(f.vm.uiState.value.title).isEqualTo("Device Owner активен")
    }

    @Test
    fun fieldEditsAreReflectedInTheState() = runTest {
        val f = Fixture(this)

        f.vm.onIntent(User.EditApkUrl("https://h/x.apk"))
        f.vm.onIntent(User.EditApkSha256("beef"))
        f.vm.onIntent(User.EditTargetPackage("a.b"))
        f.vm.onIntent(User.EditTargetUrl("https://t"))

        val state = f.vm.uiState.value
        assertThat(listOf(state.apkUrl, state.apkSha256, state.targetPackage, state.targetUrl))
            .containsExactly("https://h/x.apk", "beef", "a.b", "https://t").inOrder()
    }

    @Test
    fun d3InstallsTheApkFromTheFields() = runTest {
        val f = Fixture(this)
        f.setUrlAndHash()
        advanceUntilIdle()

        f.vm.onIntent(User.RunOne(CheckId.D3))
        advanceUntilIdle()

        assertThat(f.commands()).containsExactly("install:$apk:$sha")
        assertThat(f.row("D3").badge).isEqualTo(Badge.OK)
    }

    @Test
    fun emptyUrlBlocksD3EvenIfTheIntentArrives() = runTest {
        val f = Fixture(this)
        f.vm.onIntent(User.EditApkUrl(""))
        f.vm.onIntent(User.EditApkSha256(sha))
        advanceUntilIdle()

        f.vm.onIntent(User.RunOne(CheckId.D3))
        advanceUntilIdle()

        assertThat(f.commands()).isEmpty()
    }

    @Test
    fun emptyHashBlocksD3() = runTest {
        val f = Fixture(this)
        f.vm.onIntent(User.EditApkUrl(apk))
        advanceUntilIdle()

        f.vm.onIntent(User.RunOne(CheckId.D3))
        advanceUntilIdle()

        assertThat(f.commands()).isEmpty()
    }

    @Test
    fun installFailureShowsReasonInTheRow() = runTest {
        val f = Fixture(this)
        f.ports.installFailure = "hash_mismatch"
        f.setUrlAndHash()
        advanceUntilIdle()

        f.vm.onIntent(User.RunOne(CheckId.D3))
        advanceUntilIdle()

        assertThat(f.row("D3").badge).isEqualTo(Badge.FAIL)
        assertThat(f.row("D3").summary).isEqualTo("hash_mismatch")
    }

    @Test
    fun d4LocksTheTargetPackageAndUrl() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        f.vm.onIntent(User.EditTargetPackage("com.android.chrome"))
        f.vm.onIntent(User.EditTargetUrl("https://example.com"))

        f.vm.onIntent(User.RunOne(CheckId.D4))
        advanceUntilIdle()

        assertThat(f.commands()).containsExactly("lock:com.android.chrome:https://example.com")
    }

    @Test
    fun d5LaunchesTheTargetNow() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        f.events.clear()

        f.vm.onIntent(User.RunOne(CheckId.D5))
        advanceUntilIdle()

        assertThat(f.events).containsExactly("launch:ru.restofleet.dummy.waiter:null:DIRECT_DO")
    }

    @Test
    fun d6ReportsEveryPermissionAsTrueOrFalse() = runTest {
        val f = Fixture(this)
        f.ports.grants = linkedMapOf("ACCESS_FINE_LOCATION" to true, "PACKAGE_USAGE_STATS" to false)
        advanceUntilIdle()

        f.vm.onIntent(User.RunOne(CheckId.D6))
        advanceUntilIdle()

        assertThat(f.row("D6").badge).isEqualTo(Badge.WARN)
        assertThat(f.row("D6").summary).isEqualTo("не выдано: PACKAGE_USAGE_STATS")
    }

    @Test
    fun r10AndR13RunThroughTheCapabilityChecks() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        f.events.clear()

        f.vm.onIntent(User.RunOne(CheckId.R13))
        advanceUntilIdle()

        assertThat(f.events).containsExactly("check:R13")
    }

    @Test
    fun rowsOutsideTheScreenAreIgnored() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        f.events.clear()

        f.vm.onIntent(User.RunOne(CheckId.C1))
        f.vm.onIntent(User.RunOne(CheckId.D7))
        advanceUntilIdle()

        assertThat(f.events).isEmpty()
    }

    @Test
    fun singleRebootAsksForConfirmationAndDoesNothingUntilAnswered() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        f.events.clear()

        f.vm.effect.test {
            f.vm.onIntent(User.RunOne(CheckId.D2))
            advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(ConfirmReboot)
            assertThat(f.commands()).isEmpty()
            assertThat(f.row("D2").isRunning).isTrue()

            f.vm.onIntent(User.ConfirmReboot(true))
            advanceUntilIdle()
        }
        assertThat(f.commands()).containsExactly("reboot")
        assertThat(f.row("D2").isRunning).isFalse()
    }

    @Test
    fun decliningTheSingleRebootNeverReboots() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        f.events.clear()

        f.vm.onIntent(User.RunOne(CheckId.D2))
        advanceUntilIdle()
        f.vm.onIntent(User.ConfirmReboot(false))
        advanceUntilIdle()

        assertThat(f.commands()).isEmpty()
        assertThat(f.row("D2").isRunning).isFalse()
        assertThat(f.row("D2").summary).isEqualTo("не запускалась")
    }

    @Test
    fun answerWithoutAQuestionIsIgnored() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        f.events.clear()

        f.vm.onIntent(User.ConfirmReboot(true))
        advanceUntilIdle()

        assertThat(f.events).isEmpty()
    }

    @Test
    fun runAllWithoutDeviceOwnerNeitherAsksNorReboots() = runTest {
        val f = Fixture(this, AgentMode.NORMAL)
        f.setUrlAndHash()
        advanceUntilIdle()

        f.vm.effect.test {
            f.vm.onIntent(User.RunAll)
            advanceUntilIdle()
            expectNoEvents()
        }

        assertThat(f.events).doesNotContain("reboot")
        assertThat(f.events.filterNot { it.startsWith("check:") }).isEmpty()
        assertThat(f.row("D2").badge).isEqualTo(Badge.NA)
        assertThat(f.vm.uiState.value.isRunningAll).isFalse()
    }

    @Test
    fun deviceOwnerRowsAreIgnoredByTheViewModelInNormalMode() = runTest {
        val f = Fixture(this, AgentMode.NORMAL)
        f.setUrlAndHash()
        advanceUntilIdle()
        f.events.clear()

        listOf(CheckId.D2, CheckId.D3, CheckId.D4, CheckId.D5, CheckId.D6, CheckId.R10).forEach {
            f.vm.onIntent(User.RunOne(it))
        }
        f.vm.onIntent(User.RunLaunchFromBackground)
        advanceUntilIdle()

        assertThat(f.events).isEmpty()
    }

    @Test
    fun fetchChecksumAsksForTheSiblingFileAndFillsTheField() = runTest {
        val f = Fixture(this)

        f.vm.effect.test {
            f.vm.onIntent(User.FetchChecksum)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(ShowMessage(DeviceOwnerMessage.HashFetched))
        }

        assertThat(f.fetched).containsExactly("$apk.sha256")
        assertThat(f.vm.uiState.value.apkSha256).isEqualTo(sha)
        assertThat(f.vm.uiState.value.isFetchingHash).isFalse()
    }

    @Test
    fun fetchChecksumShowsProgressWhileWaiting() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this, fetchGate = gate)

        f.vm.onIntent(User.FetchChecksum)
        runCurrent()
        assertThat(f.vm.uiState.value.isFetchingHash).isTrue()
        f.vm.onIntent(User.FetchChecksum)
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(f.fetched).hasSize(1)
        assertThat(f.vm.uiState.value.isFetchingHash).isFalse()
    }

    @Test
    fun fetchChecksumFailureKeepsTheFieldAndShowsTheCode() = runTest {
        val f = Fixture(this, fetchAnswer = { Outcome.Failure(Code("http_404")) })
        f.vm.onIntent(User.EditApkSha256("keep"))

        f.vm.effect.test {
            f.vm.onIntent(User.FetchChecksum)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(ShowMessage(DeviceOwnerMessage.HashFailed("http_404")))
        }

        assertThat(f.vm.uiState.value.apkSha256).isEqualTo("keep")
        assertThat(f.vm.uiState.value.isFetchingHash).isFalse()
    }

    @Test
    fun fetchChecksumWithEmptyUrlDoesNothing() = runTest {
        val f = Fixture(this)
        f.vm.onIntent(User.EditApkUrl(" "))

        f.vm.onIntent(User.FetchChecksum)
        advanceUntilIdle()

        assertThat(f.fetched).isEmpty()
    }

    @Test
    fun throwingFetchResetsProgressAndShowsError() = runTest {
        val f = Fixture(this, fetchAnswer = { throw SecurityException("denied") })

        f.vm.effect.test {
            f.vm.onIntent(User.FetchChecksum)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(ShowMessage(DeviceOwnerMessage.Error("denied")))
        }
        assertThat(f.vm.uiState.value.isFetchingHash).isFalse()
    }

    @Test
    fun commandResultsGoToTheJournalForTheReport() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()

        f.vm.onIntent(User.RunOne(CheckId.D4))
        f.vm.onIntent(User.RunOne(CheckId.D6))
        advanceUntilIdle()

        assertThat(f.journal.recorded().keys).containsExactly(CheckId.D4, CheckId.D6)
    }

    @Test
    fun d5FromBackgroundWaitsThirtySeconds() = runTest {
        val f = Fixture(this)
        advanceUntilIdle()
        f.events.clear()

        f.vm.onIntent(User.RunLaunchFromBackground)
        runCurrent()
        assertThat(f.row("D5").isRunning).isTrue()
        advanceTimeBy(29_999)
        assertThat(f.events).isEmpty()

        advanceTimeBy(1)
        runCurrent()

        assertThat(f.events).containsExactly("launch:ru.restofleet.dummy.waiter:null:DIRECT_DO")
        assertThat(f.row("D5").isRunning).isFalse()
    }

    private val stepsWithoutReboot = listOf(
        "check:D1", "grant", "check:R10", "check:R13", "install:$apk:$sha",
        "launch:ru.restofleet.dummy.waiter:null:DIRECT_DO",
    )

    @Test
    fun runAllAsksForRebootConfirmationFirstBeforeAnyStep() = runTest {
        val f = Fixture(this)
        f.setUrlAndHash()
        advanceUntilIdle()
        f.events.clear()

        f.vm.effect.test {
            f.vm.onIntent(User.RunAll)
            advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(ConfirmReboot)
            assertThat(f.events).isEmpty()
            assertThat(f.vm.uiState.value.isRunningAll).isTrue()

            f.vm.onIntent(User.ConfirmReboot(false))
            advanceUntilIdle()
        }
    }

    @Test
    fun confirmedRunAllRunsTheStepsInOrderAndRebootsLastWithoutLockTask() = runTest {
        val f = Fixture(this)
        f.setUrlAndHash()
        advanceUntilIdle()
        f.events.clear()

        f.vm.effect.test {
            f.vm.onIntent(User.RunAll)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(ConfirmReboot)
            f.vm.onIntent(User.ConfirmReboot(true))
            advanceUntilIdle()
        }

        assertThat(f.events).containsExactlyElementsIn(stepsWithoutReboot + "reboot").inOrder()
        assertThat(f.events.none { it.startsWith("lock") }).isTrue()
        assertThat(f.vm.uiState.value.isRunningAll).isFalse()
        assertThat(f.vm.uiState.value.rows.none { it.isRunning }).isTrue()
    }

    @Test
    fun declinedRunAllRunsEverythingElseAndLeavesD2NotRun() = runTest {
        val f = Fixture(this)
        f.setUrlAndHash()
        advanceUntilIdle()
        f.events.clear()

        f.vm.onIntent(User.RunAll)
        advanceUntilIdle()
        f.vm.onIntent(User.ConfirmReboot(false))
        advanceUntilIdle()

        assertThat(f.events).containsExactlyElementsIn(stepsWithoutReboot).inOrder()
        assertThat(f.row("D2").summary).isEqualTo("не запускалась")
        assertThat(f.row("D2").badge).isEqualTo(Badge.NA)
        assertThat(f.row("D4").badge).isEqualTo(Badge.PENDING)
        assertThat(f.vm.uiState.value.isRunningAll).isFalse()
    }

    @Test
    fun runAllWithoutAnAnswerRunsNothingAndNeverReboots() = runTest {
        val f = Fixture(this)
        f.setUrlAndHash()
        advanceUntilIdle()
        f.events.clear()

        f.vm.onIntent(User.RunAll)
        advanceTimeBy(10 * 60_000L)
        runCurrent()

        assertThat(f.events).isEmpty()
        assertThat(f.vm.uiState.value.isRunningAll).isTrue()
    }

    @Test
    fun runAllSkipsD3WithoutUrlAndExplainsWhy() = runTest {
        val f = Fixture(this)
        f.vm.onIntent(User.EditApkUrl(""))
        advanceUntilIdle()

        f.vm.onIntent(User.RunAll)
        advanceUntilIdle()
        f.vm.onIntent(User.ConfirmReboot(false))
        advanceUntilIdle()

        assertThat(f.events.none { it.startsWith("install") }).isTrue()
        assertThat(f.row("D3").summary).isEqualTo("пропущено: нет URL")
        assertThat(f.row("D3").isRunning).isFalse()
    }

    @Test
    fun runAllSkipsD3WithoutHashAndExplainsWhy() = runTest {
        val f = Fixture(this)
        f.vm.onIntent(User.EditApkUrl(apk))
        advanceUntilIdle()

        f.vm.onIntent(User.RunAll)
        advanceUntilIdle()
        f.vm.onIntent(User.ConfirmReboot(false))
        advanceUntilIdle()

        assertThat(f.events.none { it.startsWith("install") }).isTrue()
        assertThat(f.row("D3").summary).isEqualTo("пропущено: нет хэша")
    }

    @Test
    fun runAllContinuesAfterPortFailuresWhichUseCasesIsolateIntoRows() = runTest {
        val f = Fixture(this)
        f.setUrlAndHash()
        advanceUntilIdle()
        f.events.clear()
        f.ports.failWith = IllegalStateException("boom")

        f.vm.effect.test {
            f.vm.onIntent(User.RunAll)
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(ConfirmReboot)
            f.vm.onIntent(User.ConfirmReboot(false))
            advanceUntilIdle()

            expectNoEvents()
        }
        assertThat(f.row("R10").badge).isEqualTo(Badge.OK)
        assertThat(f.row("D6").badge).isEqualTo(Badge.FAIL)
        assertThat(f.row("D6").summary).isEqualTo("exception:IllegalStateException")
        assertThat(f.row("D6").isRunning).isFalse()
        assertThat(f.row("D5").badge).isEqualTo(Badge.FAIL)
        assertThat(f.vm.uiState.value.isRunningAll).isFalse()
    }

    @Test
    fun secondRunAllAndRowRunsAreIgnoredWhileRunningAll() = runTest {
        val f = Fixture(this)
        f.setUrlAndHash()
        advanceUntilIdle()
        f.events.clear()

        f.vm.onIntent(User.RunAll)
        advanceUntilIdle()
        f.vm.onIntent(User.RunAll)
        f.vm.onIntent(User.RunOne(CheckId.D6))
        f.vm.onIntent(User.RunLaunchFromBackground)
        f.vm.onIntent(User.ConfirmReboot(true))
        advanceUntilIdle()

        assertThat(f.events.count { it == "grant" }).isEqualTo(1)
        assertThat(f.events.count { it.startsWith("launch") }).isEqualTo(1)
        assertThat(f.events.count { it == "reboot" }).isEqualTo(1)
    }

    @Test
    fun runAllIsIgnoredWhileASingleRowIsRunning() = runTest {
        val f = Fixture(this)
        f.setUrlAndHash()
        advanceUntilIdle()
        f.events.clear()
        f.vm.onIntent(User.RunLaunchFromBackground)
        runCurrent()

        f.vm.effect.test {
            f.vm.onIntent(User.RunAll)
            runCurrent()
            expectNoEvents()
        }
        advanceUntilIdle()

        assertThat(f.events).containsExactly("launch:ru.restofleet.dummy.waiter:null:DIRECT_DO")
        assertThat(f.vm.uiState.value.isRunningAll).isFalse()
    }

    @Test
    fun hashOfAnOldAddressIsNotAppliedToAChangedUrl() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this, fetchGate = gate)

        f.vm.effect.test {
            f.vm.onIntent(User.FetchChecksum)
            runCurrent()
            f.vm.onIntent(User.EditApkUrl("https://10.0.2.2:8443/waiter-4.14.apk"))
            gate.complete(Unit)
            advanceUntilIdle()
            expectNoEvents()
        }

        assertThat(f.vm.uiState.value.apkSha256).isEmpty()
        assertThat(f.vm.uiState.value.isFetchingHash).isFalse()
    }

    @Test
    fun hashIsAppliedWhenOnlyWhitespaceAroundTheUrlChanged() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this, fetchGate = gate)

        f.vm.onIntent(User.FetchChecksum)
        runCurrent()
        f.vm.onIntent(User.EditApkUrl("  $apk "))
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(f.vm.uiState.value.apkSha256).isEqualTo(sha)
    }

    @Test
    fun unhandledExceptionHandlerLogsInsteadOfCrashing() = runTest {
        val logged = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + deviceOwnerExceptionHandler { logged += it })

        scope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { throw IllegalStateException("late") }.join()

        assertThat(logged.single()).hasMessageThat().isEqualTo("late")
    }
}
