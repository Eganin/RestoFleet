package ru.restofleet.agent.presentation.deviceowner.viewmodel.mapper

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.presentation.common.Badge
import ru.restofleet.agent.presentation.deviceowner.viewmodel.DeviceOwnerRowUi
import ru.restofleet.agent.presentation.deviceowner.viewmodel.model.DeviceOwnerSession

class DeviceOwnerUiStateMapperTest {
    private val mapper = DeviceOwnerUiStateMapper()
    private val owner = DeviceInfo(36, "Pixel", true, AgentMode.DEVICE_OWNER, "0.1")
    private val normal = owner.copy(mode = AgentMode.NORMAL)

    private fun result(id: CheckId, status: CheckStatus, raw: Map<String, String> = emptyMap(), note: String? = null) =
        CheckResult(id, status, raw, note, 1L)

    private fun session(
        device: DeviceInfo? = owner,
        vararg results: CheckResult,
        apkUrl: String = "https://h/a.apk",
        sha: String = "a".repeat(64),
        running: Set<CheckId> = emptySet(),
        isRunningAll: Boolean = false,
    ) = DeviceOwnerSession(
        device = device,
        results = results.associateBy { it.id },
        apkUrl = apkUrl,
        apkSha256 = sha,
        running = running,
        isRunningAll = isRunningAll,
    )

    private fun DeviceOwnerSessionRows(s: DeviceOwnerSession) = mapper.map(s).rows.associateBy { it.id }

    @Test
    fun titleSaysDeviceOwnerIsActive() {
        val state = mapper.map(session(owner))

        assertThat(state.title).isEqualTo("Device Owner активен")
        assertThat(state.isDeviceOwner).isTrue()
    }

    @Test
    fun titleSaysNormalModeWithoutDeviceOwner() {
        val state = mapper.map(session(normal))

        assertThat(state.title).isEqualTo("Обычный режим")
        assertThat(state.isDeviceOwner).isFalse()
    }

    @Test
    fun titleWhileTheModeIsUnknown() {
        val state = mapper.map(session(null))

        assertThat(state.title).isEqualTo("Проверка режима…")
        assertThat(state.isDeviceOwner).isFalse()
    }

    @Test
    fun ownerIsTheAgentPackageOnlyInDeviceOwnerMode() {
        assertThat(mapper.map(session()).owner).isEqualTo("ru.restofleet.agent")
        assertThat(mapper.map(session(normal)).owner).isEqualTo("—")
        assertThat(mapper.map(session(device = null)).owner).isEqualTo("—")
    }

    @Test
    fun rowsFollowTheMockupOrder() {
        assertThat(mapper.map(session()).rows.map { it.id })
            .containsExactly("D1", "D2", "D3", "D4", "D5", "D6", "R10", "R13").inOrder()
        val d3 = mapper.map(session()).rows.first { it.id == "D3" }
        assertThat(d3.title).isEqualTo("D3 Тихая установка APK, SHA-256")
    }

    @Test
    fun withoutDeviceOwnerOnlyTheDeviceOwnerRowsAreNotApplicableWithTheFixedText() {
        val rows = DeviceOwnerSessionRows(
            session(normal, result(CheckId.D1, CheckStatus.UNAVAILABLE, note = "not_device_owner")),
        )

        listOf("D2", "D3", "D4", "D5", "D6", "R10").forEach { id ->
            assertThat(rows.getValue(id).badge).isEqualTo(Badge.NA)
            assertThat(rows.getValue(id).summary).isEqualTo("только в режиме Device Owner")
            assertThat(rows.getValue(id).enabled).isFalse()
        }
        assertThat(rows.getValue("D1").badge).isEqualTo(Badge.FAIL)
        assertThat(rows.getValue("D1").summary).isEqualTo("not_device_owner")
        assertThat(rows.getValue("D1").enabled).isTrue()
        assertThat(rows.getValue("R13").summary).isEqualTo("⏳ не запускалась")
        assertThat(rows.getValue("R13").enabled).isTrue()
    }

    @Test
    fun notApplicableTextOverridesAStaleResultInNormalMode() {
        val rows = DeviceOwnerSessionRows(session(normal, result(CheckId.D6, CheckStatus.OK, mapOf("a" to "true"))))

        assertThat(rows.getValue("D6").badge).isEqualTo(Badge.NA)
        assertThat(rows.getValue("D6").summary).isEqualTo("только в режиме Device Owner")
    }

    @Test
    fun withDeviceOwnerRowsShowTheirResultsAndStayEnabled() {
        val rows = DeviceOwnerSessionRows(
            session(
                owner,
                result(
                    CheckId.D6,
                    CheckStatus.LIMITED,
                    linkedMapOf("ACCESS_FINE_LOCATION" to "true", "PACKAGE_USAGE_STATS" to "false"),
                ),
                result(CheckId.R10, CheckStatus.OK, mapOf("cpuC" to "41.0")),
                result(CheckId.D3, CheckStatus.UNAVAILABLE, note = "hash_mismatch"),
            ),
        )

        assertThat(rows.getValue("D6").badge).isEqualTo(Badge.WARN)
        assertThat(rows.getValue("D6").summary).isEqualTo("ACCESS_FINE_LOCATION true · PACKAGE_USAGE_STATS false")
        assertThat(rows.getValue("R10").badge).isEqualTo(Badge.OK)
        assertThat(rows.getValue("D3").badge).isEqualTo(Badge.FAIL)
        assertThat(rows.getValue("D3").summary).isEqualTo("hash_mismatch")
        assertThat(rows.getValue("D2").badge).isEqualTo(Badge.PENDING)
        assertThat(rows.getValue("D2").enabled).isTrue()
    }

    @Test
    fun emptyApkUrlBlocksD3() {
        assertThat(DeviceOwnerSessionRows(session(apkUrl = "")).getValue("D3").enabled).isFalse()
        assertThat(DeviceOwnerSessionRows(session(apkUrl = "   ")).getValue("D3").enabled).isFalse()
    }

    @Test
    fun emptyHashBlocksD3() {
        assertThat(DeviceOwnerSessionRows(session(sha = "")).getValue("D3").enabled).isFalse()
    }

    @Test
    fun d3IsEnabledWhenBothUrlAndHashAreSet() {
        assertThat(DeviceOwnerSessionRows(session()).getValue("D3").enabled).isTrue()
    }

    @Test
    fun fetchHashButtonNeedsAnUrlAndNoRunningFetch() {
        assertThat(mapper.map(session(apkUrl = "")).canFetchHash).isFalse()
        assertThat(mapper.map(session()).canFetchHash).isTrue()
        assertThat(mapper.map(session().copy(isFetchingHash = true)).canFetchHash).isFalse()
        assertThat(mapper.map(session().copy(isFetchingHash = true)).isFetchingHash).isTrue()
    }

    @Test
    fun serialIsTheMaskedValueFromR13() {
        val state = mapper.map(session(owner, result(CheckId.R13, CheckStatus.OK, mapOf("serial" to "****4QZ0"))))

        assertThat(state.serial).isEqualTo("****4QZ0")
    }

    @Test
    fun serialIsADashWithoutAValue() {
        assertThat(mapper.map(session(owner)).serial).isEqualTo("—")
        assertThat(
            mapper.map(session(owner, result(CheckId.R13, CheckStatus.UNAVAILABLE, note = "requires_device_owner")))
                .serial,
        ).isEqualTo("—")
    }

    @Test
    fun runningRowShowsSpinnerAndIsNotRunnableAgain() {
        val row = DeviceOwnerSessionRows(session(running = setOf(CheckId.D3))).getValue("D3")

        assertThat(row.isRunning).isTrue()
        assertThat(row.enabled).isFalse()
    }

    @Test
    fun runAllDisablesEveryRowButKeepsFlag() {
        val state = mapper.map(session(isRunningAll = true))

        assertThat(state.isRunningAll).isTrue()
        assertThat(state.rows.none(DeviceOwnerRowUi::enabled)).isTrue()
    }

    @Test
    fun onlyD5HasTheDelayedVariant() {
        assertThat(mapper.map(session()).rows.filter { it.hasDelayedVariant }.map { it.id }).containsExactly("D5")
    }

    @Test
    fun fieldsAreCopiedFromTheSession() {
        val state = mapper.map(session().copy(targetPackage = "a.b", targetUrl = "https://t"))

        assertThat(state.apkUrl).isEqualTo("https://h/a.apk")
        assertThat(state.apkSha256).isEqualTo("a".repeat(64))
        assertThat(state.targetPackage).isEqualTo("a.b")
        assertThat(state.targetUrl).isEqualTo("https://t")
    }

    @Test
    fun runAllButtonIsEnabledOnlyWhenNothingRuns() {
        assertThat(mapper.map(session()).canRunAll).isTrue()
        assertThat(mapper.map(session(running = setOf(CheckId.D5))).canRunAll).isFalse()
        assertThat(mapper.map(session(isRunningAll = true)).canRunAll).isFalse()
    }

    @Test
    fun rowsRequiringDeviceOwnerAreDisabledWhileTheModeIsUnknown() {
        val rows = DeviceOwnerSessionRows(session(null))

        listOf("D2", "D3", "D4", "D5", "D6", "R10").forEach { assertThat(rows.getValue(it).enabled).isFalse() }
        assertThat(rows.getValue("D1").enabled).isTrue()
        assertThat(rows.getValue("R13").enabled).isTrue()
    }

    @Test
    fun d6NoteCodesAreShownAsRussianText() {
        val notGranted = DeviceOwnerSessionRows(
            session(owner, result(CheckId.D6, CheckStatus.LIMITED, note = "not_granted:A_PERM,B_PERM")),
        )
        val nothing = DeviceOwnerSessionRows(
            session(owner, result(CheckId.D6, CheckStatus.LIMITED, note = "nothing_to_grant")),
        )
        val failed = DeviceOwnerSessionRows(
            session(owner, result(CheckId.D6, CheckStatus.UNAVAILABLE, note = "exception:X")),
        )

        assertThat(notGranted.getValue("D6").summary).isEqualTo("не выдано: A_PERM, B_PERM")
        assertThat(nothing.getValue("D6").summary).isEqualTo("нет разрешений для выдачи")
        assertThat(failed.getValue("D6").summary).isEqualTo("exception:X")
    }
}
