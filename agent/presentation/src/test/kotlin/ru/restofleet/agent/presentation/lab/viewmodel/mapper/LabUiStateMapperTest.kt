package ru.restofleet.agent.presentation.lab.viewmodel.mapper

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.presentation.lab.viewmodel.Badge
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabFilter
import ru.restofleet.agent.presentation.lab.viewmodel.model.LabSession
import ru.restofleet.agent.presentation.lab.viewmodel.model.SettingsTarget
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CheckGroup
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.capability.DeviceInfo

class LabUiStateMapperTest {
    private val mapper = LabUiStateMapper()

    private fun result(
        id: CheckId,
        status: CheckStatus,
        raw: Map<String, String> = emptyMap(),
        note: String? = null,
    ) = CheckResult(id, status, raw, note, 1L)

    private fun session(vararg results: CheckResult, filter: LabFilter = LabFilter.ALL) =
        LabSession(results = results.associateBy { it.id }, filter = filter)

    private val mixed = arrayOf(
        result(CheckId.R1, CheckStatus.OK),
        result(CheckId.R2, CheckStatus.LIMITED),
        result(CheckId.R3, CheckStatus.UNAVAILABLE),
        result(CheckId.R10, CheckStatus.NOT_APPLICABLE),
        result(CheckId.R4, CheckStatus.NOT_CHECKED),
        result(CheckId.D1, CheckStatus.LIMITED),
    )

    @Test
    fun headerWithoutDeviceIsPlainTitle() {
        assertThat(mapper.map(LabSession()).header).isEqualTo("Capability Lab")
    }

    @Test
    fun headerShowsApiLevelOfLoadedDevice() {
        val device = DeviceInfo(36, "Pixel", true, AgentMode.NORMAL, "0.1.0")

        assertThat(mapper.map(LabSession(device = device)).header).isEqualTo("Capability Lab · API 36")
    }

    @Test
    fun tabsShowCountsOfAllProblemsAndDeviceOwnerChecks() {
        val tabs = mapper.map(session(*mixed)).tabs

        assertThat(tabs.map { it.label }).containsExactly("Все · 43", "Проблемы · 3", "DO · 7").inOrder()
        assertThat(tabs.map { it.filter })
            .containsExactly(LabFilter.ALL, LabFilter.PROBLEMS, LabFilter.DEVICE_OWNER).inOrder()
    }

    @Test
    fun problemsCountIsZeroBeforeAnyRun() {
        assertThat(mapper.map(LabSession()).tabs[1].label).isEqualTo("Проблемы · 0")
    }

    @Test
    fun onlySelectedTabIsMarked() {
        val tabs = mapper.map(session(filter = LabFilter.PROBLEMS)).tabs

        assertThat(tabs.filter { it.selected }.map { it.filter }).containsExactly(LabFilter.PROBLEMS)
    }

    @Test
    fun filterAllListsEveryCheckInCatalogOrder() {
        val rows = mapper.map(session(*mixed)).rows

        assertThat(rows.map { it.id }).containsExactlyElementsIn(CheckId.entries.map { it.name }).inOrder()
    }

    @Test
    fun filterProblemsKeepsOnlyLimitedAndUnavailable() {
        val rows = mapper.map(session(*mixed, filter = LabFilter.PROBLEMS)).rows

        assertThat(rows.map { it.id }).containsExactly("R2", "R3", "D1").inOrder()
    }

    @Test
    fun filterDeviceOwnerKeepsOnlyThatGroup() {
        val rows = mapper.map(session(*mixed, filter = LabFilter.DEVICE_OWNER)).rows

        assertThat(rows.map { it.id }).containsExactlyElementsIn(
            CheckId.entries.filter { it.group == CheckGroup.DEVICE_OWNER }.map { it.name },
        ).inOrder()
        assertThat(rows).hasSize(7)
    }

    @Test
    fun badgeFollowsStatus() {
        val rows = mapper.map(session(*mixed)).rows.associateBy { it.id }

        assertThat(rows.getValue("R1").badge).isEqualTo(Badge.OK)
        assertThat(rows.getValue("R2").badge).isEqualTo(Badge.WARN)
        assertThat(rows.getValue("R3").badge).isEqualTo(Badge.FAIL)
        assertThat(rows.getValue("R10").badge).isEqualTo(Badge.NA)
        assertThat(rows.getValue("R4").badge).isEqualTo(Badge.NA)
        assertThat(rows.getValue("R5").badge).isEqualTo(Badge.PENDING)
    }

    @Test
    fun rowWithoutResultIsPendingWithHourglass() {
        val row = mapper.map(LabSession()).rows.first()

        assertThat(row.badge).isEqualTo(Badge.PENDING)
        assertThat(row.summary).isEqualTo("⏳ не запускалась")
        assertThat(row.canSend).isFalse()
    }

    @Test
    fun titleIsIdAndCatalogTitle() {
        val row = mapper.map(LabSession()).rows.first { it.id == "R1" }

        assertThat(row.title).isEqualTo("R1 Заряд и питание")
    }

    @Test
    fun summaryJoinsRawPairsWithMiddleDot() {
        val raw = linkedMapOf("level" to "87", "plugged" to "AC", "temp" to "38.4")
        val row = mapper.map(session(result(CheckId.R1, CheckStatus.OK, raw))).rows.first { it.id == "R1" }

        assertThat(row.summary).isEqualTo("level 87 · plugged AC · temp 38.4")
        assertThat(row.canSend).isTrue()
    }

    @Test
    fun noteWinsOverRaw() {
        val r = result(CheckId.R5, CheckStatus.LIMITED, mapOf("ssid" to "<unknown ssid>"), "нужна геолокация")

        assertThat(mapper.map(session(r)).rows.first { it.id == "R5" }.summary).isEqualTo("нужна геолокация")
    }

    @Test
    fun resultWithoutRawAndNoteHasEmptySummary() {
        val row = mapper.map(session(result(CheckId.R1, CheckStatus.OK))).rows.first { it.id == "R1" }

        assertThat(row.summary).isEmpty()
    }

    @Test
    fun runningFlagsComeFromSession() {
        val state = mapper.map(
            LabSession(running = setOf(CheckId.R2), isRunningAll = true, isExporting = true),
        )

        assertThat(state.rows.filter { it.isRunning }.map { it.id }).containsExactly("R2")
        assertThat(state.isRunningAll).isTrue()
        assertThat(state.isExporting).isTrue()
    }

    @Test
    fun onlyLifecycleSettingsRowOffersSettingsButtons() {
        val rows = mapper.map(LabSession()).rows.associateBy { it.id }

        assertThat(rows.getValue("L4").settingsTargets).containsExactly(
            SettingsTarget.NOTIFICATIONS,
            SettingsTarget.USAGE_ACCESS,
            SettingsTarget.BATTERY_OPTIMIZATION,
        ).inOrder()
        assertThat(rows.getValue("L3").settingsTargets).isEmpty()
    }
}
