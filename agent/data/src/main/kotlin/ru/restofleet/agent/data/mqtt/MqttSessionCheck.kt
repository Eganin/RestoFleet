package ru.restofleet.agent.data.mqtt

import kotlinx.coroutines.flow.StateFlow
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.shared.kernel.Clock

/**
 * M1 «TLS 8883 с CA стенда»: показывает в Lab, подключена ли сессия, а если нет, то по какой причине (код
 * [MqttError.code]: `not_configured`, `ca_missing`, `ca_invalid`, `tls_error`, `auth_failed`, ...). Никаких секретов:
 * только `host:port`, `connId` и код. Нет учётки или CA ещё не значит сбой: такая проверка `NOT_CHECKED`.
 */
class MqttSessionCheck internal constructor(
    private val status: StateFlow<SessionStatus>,
    private val clock: Clock,
) : CapabilityCheck {
    override val id = CheckId.M1

    override suspend fun run(): CheckResult {
        val s = status.value
        val raw = buildMap {
            s.endpoint?.let { put("endpoint", it) }
            s.connId?.let { put("connId", it) }
            s.error?.let { put("error", it.code) }
        }
        val (result, note) = when {
            s.state == SessionState.CONNECTED && s.error == null -> CheckStatus.OK to null
            s.state == SessionState.CONNECTED ->
                CheckStatus.LIMITED to "connected, but online/subscribe failed: ${s.error?.code}"
            s.error == MqttError.NotConfigured || s.error == MqttError.CaMissing ->
                CheckStatus.NOT_CHECKED to "session not started: ${s.error.code}"
            s.error != null -> CheckStatus.UNAVAILABLE to "session is down: ${s.error.code}"
            else -> CheckStatus.NOT_CHECKED to "connecting"
        }
        return CheckResult(id, result, raw, note, clock.nowMillis())
    }
}
