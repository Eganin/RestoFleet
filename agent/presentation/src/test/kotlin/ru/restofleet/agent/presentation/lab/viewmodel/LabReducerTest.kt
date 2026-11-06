package ru.restofleet.agent.presentation.lab.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.presentation.lab.viewmodel.LabAction.DeviceLoaded
import ru.restofleet.agent.presentation.lab.viewmodel.LabAction.ExportingChanged
import ru.restofleet.agent.presentation.lab.viewmodel.LabAction.FilterSelected
import ru.restofleet.agent.presentation.lab.viewmodel.LabAction.ResultsArrived
import ru.restofleet.agent.presentation.lab.viewmodel.LabAction.RunStarted
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabFilter
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabSession
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.capability.DeviceInfo

class LabReducerTest {
    private val reducer = LabReducer()

    private fun result(id: CheckId, status: CheckStatus = CheckStatus.OK) =
        CheckResult(id, status, emptyMap(), null, 1L)

    @Test
    fun runStartedAddsIdsToRunning() {
        val prev = LabSession(running = setOf(CheckId.P1))

        val next = reducer.reduce(RunStarted(setOf(CheckId.R1, CheckId.R2)), prev)

        assertThat(next.running).containsExactly(CheckId.P1, CheckId.R1, CheckId.R2)
        assertThat(next.isRunningAll).isFalse()
    }

    @Test
    fun runStartedForAllRaisesRunningAllFlag() {
        val next = reducer.reduce(RunStarted(CheckId.entries.toSet(), isAll = true), LabSession())

        assertThat(next.isRunningAll).isTrue()
        assertThat(next.running).hasSize(CheckId.entries.size)
    }

    @Test
    fun resultsArrivedStoreResultsAndRemoveIdsFromRunning() {
        val prev = LabSession(running = setOf(CheckId.R1, CheckId.R2, CheckId.R3))

        val next = reducer.reduce(ResultsArrived(listOf(result(CheckId.R1), result(CheckId.R2))), prev)

        assertThat(next.running).containsExactly(CheckId.R3)
        assertThat(next.results.keys).containsExactly(CheckId.R1, CheckId.R2)
        assertThat(next.results.getValue(CheckId.R1).status).isEqualTo(CheckStatus.OK)
    }

    @Test
    fun newResultReplacesOldOneForTheSameCheck() {
        val prev = LabSession(results = mapOf(CheckId.R1 to result(CheckId.R1, CheckStatus.OK)))

        val next = reducer.reduce(ResultsArrived(listOf(result(CheckId.R1, CheckStatus.LIMITED))), prev)

        assertThat(next.results.getValue(CheckId.R1).status).isEqualTo(CheckStatus.LIMITED)
    }

    @Test
    fun runningAllEndsWhenLastResultArrives() {
        val started = reducer.reduce(RunStarted(setOf(CheckId.R1, CheckId.R2), isAll = true), LabSession())

        val afterFirst = reducer.reduce(ResultsArrived(listOf(result(CheckId.R1))), started)
        val afterSecond = reducer.reduce(ResultsArrived(listOf(result(CheckId.R2))), afterFirst)

        assertThat(afterFirst.isRunningAll).isTrue()
        assertThat(afterSecond.isRunningAll).isFalse()
    }

    @Test
    fun filterSelectedChangesFilter() {
        val next = reducer.reduce(FilterSelected(LabFilter.PROBLEMS), LabSession())

        assertThat(next.filter).isEqualTo(LabFilter.PROBLEMS)
    }

    @Test
    fun sameFilterReturnsSameInstance() {
        val prev = LabSession(filter = LabFilter.DEVICE_OWNER)

        val next = reducer.reduce(FilterSelected(LabFilter.DEVICE_OWNER), prev)

        assertThat(next).isSameInstanceAs(prev)
    }

    @Test
    fun deviceLoadedStoresDevice() {
        val device = DeviceInfo(36, "Pixel", true, AgentMode.NORMAL, "0.1.0")

        assertThat(reducer.reduce(DeviceLoaded(device), LabSession()).device).isEqualTo(device)
    }

    @Test
    fun exportingChangedTogglesFlag() {
        val on = reducer.reduce(ExportingChanged(true), LabSession())

        assertThat(on.isExporting).isTrue()
        assertThat(reducer.reduce(ExportingChanged(false), on).isExporting).isFalse()
    }
}
