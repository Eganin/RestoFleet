package ru.restofleet.agent.presentation.status.viewmodel

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.model.AgentStatus
import ru.restofleet.agent.domain.model.ConnectionInfo
import ru.restofleet.agent.domain.model.RegistrationState
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.agent.presentation.R
import ru.restofleet.agent.presentation.common.StringSpec
import ru.restofleet.agent.presentation.status.viewmodel.mapper.StatusUiStateMapper
import ru.restofleet.agent.presentation.status.viewmodel.model.StatusSession

class StatusReducerTest {
    private val reducer = StatusReducer()
    private val snapshot = AgentStatus(
        RegistrationState.NotRegistered, ConnectionInfo(SessionState.CONNECTED), null, null, null, null, emptyList(),
    )

    @Test
    fun failedRefreshKeepsThePreviousSnapshot() {
        val loaded = reducer.reduce(StatusAction.Loaded(snapshot), StatusSession())

        val refreshing = reducer.reduce(StatusAction.RefreshStarted, loaded)
        val failed = reducer.reduce(StatusAction.RefreshFinished, refreshing)

        assertThat(refreshing.isRefreshing).isTrue()
        assertThat(failed.status).isEqualTo(snapshot)
        assertThat(failed.isRefreshing).isFalse()
    }

    @Test
    fun unknownValuesAreShownAsDashes() {
        val ui = StatusUiStateMapper().map(StatusSession(status = snapshot))

        assertThat(ui.device.map { it.value }).containsExactly(
            StringSpec(R.string.value_none), StringSpec(R.string.value_none), StringSpec(R.string.value_none),
        )
        assertThat(ui.permissions.map { it.state }).containsExactly(
            StringSpec(R.string.perm_unknown), StringSpec(R.string.perm_unknown), StringSpec(R.string.perm_unknown),
        )
    }
}
