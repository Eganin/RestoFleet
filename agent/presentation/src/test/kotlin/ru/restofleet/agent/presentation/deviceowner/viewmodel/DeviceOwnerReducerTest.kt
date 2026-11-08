package ru.restofleet.agent.presentation.deviceowner.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.ApkSha256Changed
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.ApkUrlChanged
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.DeviceLoaded
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.FetchingHashChanged
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.ResultsArrived
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.RunFinished
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.RunStarted
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.TargetPackageChanged
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerAction.TargetUrlChanged
import ru.restofleet.agent.presentation.deviceowner.viewmodel.model.DeviceOwnerSession

class DeviceOwnerReducerTest {
    private val reducer = DeviceOwnerReducer()

    private fun result(id: CheckId, status: CheckStatus = CheckStatus.OK) =
        CheckResult(id, status, emptyMap(), null, 1L)

    @Test
    fun defaultsPointAtTheStandApkAndTheDummyWaiter() {
        val session = DeviceOwnerSession()

        assertThat(session.apkUrl).isEqualTo("https://10.0.2.2:8443/waiter-4.13.apk")
        assertThat(session.apkSha256).isEmpty()
        assertThat(session.targetPackage).isEqualTo("ru.restofleet.dummy.waiter")
        assertThat(session.targetUrl).isEmpty()
    }

    @Test
    fun deviceLoadedStoresDevice() {
        val device = DeviceInfo(36, "Pixel", true, AgentMode.DEVICE_OWNER, "0.1")

        assertThat(reducer.reduce(DeviceLoaded(device), DeviceOwnerSession()).device).isEqualTo(device)
    }

    @Test
    fun runStartedAddsIdsAndMarksRunAllOnlyWhenAsked() {
        val one = reducer.reduce(RunStarted(setOf(CheckId.D3)), DeviceOwnerSession(running = setOf(CheckId.D1)))
        val all = reducer.reduce(RunStarted(setOf(CheckId.D2), isAll = true), one)

        assertThat(one.running).containsExactly(CheckId.D1, CheckId.D3)
        assertThat(one.isRunningAll).isFalse()
        assertThat(all.isRunningAll).isTrue()
    }

    @Test
    fun runFinishedRemovesIdsAndClearsRunAllWhenNothingIsLeft() {
        val prev = DeviceOwnerSession(running = setOf(CheckId.D2, CheckId.D3), isRunningAll = true)

        val partial = reducer.reduce(RunFinished(setOf(CheckId.D3)), prev)
        val done = reducer.reduce(RunFinished(setOf(CheckId.D2)), partial)

        assertThat(partial.running).containsExactly(CheckId.D2)
        assertThat(partial.isRunningAll).isTrue()
        assertThat(done.running).isEmpty()
        assertThat(done.isRunningAll).isFalse()
    }

    @Test
    fun resultsReplaceOldOnesAndStopTheirRows() {
        val prev = DeviceOwnerSession(
            results = mapOf(CheckId.D6 to result(CheckId.D6, CheckStatus.LIMITED)),
            running = setOf(CheckId.D6, CheckId.D3),
            isRunningAll = true,
        )

        val next = reducer.reduce(ResultsArrived(listOf(result(CheckId.D6), result(CheckId.D1))), prev)

        assertThat(next.results[CheckId.D6]?.status).isEqualTo(CheckStatus.OK)
        assertThat(next.results.keys).containsExactly(CheckId.D6, CheckId.D1)
        assertThat(next.running).containsExactly(CheckId.D3)
        assertThat(next.isRunningAll).isTrue()
    }

    @Test
    fun lastResultEndsRunAll() {
        val prev = DeviceOwnerSession(running = setOf(CheckId.D6), isRunningAll = true)

        assertThat(reducer.reduce(ResultsArrived(listOf(result(CheckId.D6))), prev).isRunningAll).isFalse()
    }

    @Test
    fun everyFieldActionChangesOnlyItsOwnField() {
        val base = DeviceOwnerSession()

        assertThat(reducer.reduce(ApkUrlChanged("u"), base)).isEqualTo(base.copy(apkUrl = "u"))
        assertThat(reducer.reduce(ApkSha256Changed("h"), base)).isEqualTo(base.copy(apkSha256 = "h"))
        assertThat(reducer.reduce(TargetPackageChanged("p"), base)).isEqualTo(base.copy(targetPackage = "p"))
        assertThat(reducer.reduce(TargetUrlChanged("t"), base)).isEqualTo(base.copy(targetUrl = "t"))
        assertThat(reducer.reduce(FetchingHashChanged(true), base)).isEqualTo(base.copy(isFetchingHash = true))
    }
}
