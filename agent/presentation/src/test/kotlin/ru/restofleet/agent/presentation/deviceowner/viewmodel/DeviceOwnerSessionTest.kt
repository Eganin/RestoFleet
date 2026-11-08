package ru.restofleet.agent.presentation.deviceowner.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.DeviceInfo
import ru.restofleet.agent.presentation.deviceowner.viewmodel.model.DeviceOwnerSession
import ru.restofleet.agent.presentation.deviceowner.viewmodel.model.DEVICE_OWNER_ONLY
import ru.restofleet.agent.presentation.deviceowner.viewmodel.model.canRun
import ru.restofleet.agent.presentation.deviceowner.viewmodel.model.canRunAll

/** Единое правило «строку можно запустить» — им пользуются ViewModel и маппер. */
class DeviceOwnerSessionTest {
    private fun device(mode: AgentMode) = DeviceInfo(36, "Pixel", true, mode, "0.1")
    private fun session(mode: AgentMode?, sha: String = "a".repeat(64), url: String = "https://h/a.apk") =
        DeviceOwnerSession(device = mode?.let(::device), apkUrl = url, apkSha256 = sha)

    @Test
    fun deviceOwnerOnlyRowsNeedAKnownDeviceOwnerRole() {
        for (id in DEVICE_OWNER_ONLY) {
            assertThat(session(AgentMode.DEVICE_OWNER).canRun(id)).isTrue()
            assertThat(session(AgentMode.NORMAL).canRun(id)).isFalse()
            assertThat(session(null).canRun(id)).isFalse()
        }
    }

    @Test
    fun d1AndR13AreRunnableInAnyMode() {
        for (mode in listOf(AgentMode.DEVICE_OWNER, AgentMode.NORMAL, null)) {
            assertThat(session(mode).canRun(CheckId.D1)).isTrue()
            assertThat(session(mode).canRun(CheckId.R13)).isTrue()
        }
    }

    @Test
    fun d3NeedsBothUrlAndHash() {
        assertThat(session(AgentMode.DEVICE_OWNER, url = " ").canRun(CheckId.D3)).isFalse()
        assertThat(session(AgentMode.DEVICE_OWNER, sha = "").canRun(CheckId.D3)).isFalse()
        assertThat(session(AgentMode.DEVICE_OWNER).canRun(CheckId.D3)).isTrue()
    }

    @Test
    fun runningRowOrRunAllBlocksRows() {
        val owner = session(AgentMode.DEVICE_OWNER)

        assertThat(owner.copy(running = setOf(CheckId.D5)).canRun(CheckId.D5)).isFalse()
        assertThat(owner.copy(running = setOf(CheckId.D5)).canRun(CheckId.D6)).isTrue()
        assertThat(owner.copy(isRunningAll = true).canRun(CheckId.D1)).isFalse()
    }

    @Test
    fun rowsOutsideTheScreenAreNeverRunnable() {
        assertThat(session(AgentMode.DEVICE_OWNER).canRun(CheckId.C1)).isFalse()
        assertThat(session(AgentMode.DEVICE_OWNER).canRun(CheckId.D7)).isFalse()
    }

    @Test
    fun runAllNeedsNothingRunning() {
        assertThat(DeviceOwnerSession().canRunAll).isTrue()
        assertThat(DeviceOwnerSession(running = setOf(CheckId.D3)).canRunAll).isFalse()
        assertThat(DeviceOwnerSession(isRunningAll = true, running = setOf(CheckId.D3)).canRunAll).isFalse()
        assertThat(DeviceOwnerSession(isRunningAll = true).canRunAll).isFalse()
    }
}
