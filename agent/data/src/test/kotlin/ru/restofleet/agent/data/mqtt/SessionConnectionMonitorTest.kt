package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import ru.restofleet.agent.domain.model.ConnectionInfo
import ru.restofleet.agent.domain.port.SessionState

class SessionConnectionMonitorTest {
    @Test
    fun currentStatusIsMappedWithErrorCode() {
        val status = MutableStateFlow(SessionStatus(SessionState.CONNECTED, null, "10.0.2.2:8883", "k-1"))
        val monitor = SessionConnectionMonitor(status)

        assertThat(monitor.current()).isEqualTo(ConnectionInfo(SessionState.CONNECTED, "k-1", null, "10.0.2.2:8883"))

        status.value = SessionStatus(SessionState.DISCONNECTED, MqttError.NotConfigured)
        assertThat(monitor.current().errorCode).isEqualTo(MqttError.NotConfigured.code)
        assertThat(monitor.current().connId).isNull()
    }
}
