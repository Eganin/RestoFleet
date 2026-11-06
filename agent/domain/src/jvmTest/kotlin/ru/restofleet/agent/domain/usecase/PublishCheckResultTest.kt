package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.error.MqttNotConfigured
import ru.restofleet.agent.domain.port.CheckResultPublisher
import ru.restofleet.shared.kernel.Outcome

class PublishCheckResultTest {
    private val result = CheckResult(CheckId.R1, CheckStatus.OK, mapOf("level" to "87"), null, 1L)

    @Test
    fun passesTheResultToThePublisherAndReturnsItsOutcome() = runTest {
        val sent = mutableListOf<CheckResult>()
        val publisher = CheckResultPublisher {
            sent += it
            Outcome.Success(Unit)
        }

        val outcome = PublishCheckResult(publisher)(result)

        assertThat(sent).containsExactly(result)
        assertThat(outcome).isEqualTo(Outcome.Success(Unit))
    }

    @Test
    fun propagatesPublisherFailure() = runTest {
        val outcome = PublishCheckResult { Outcome.Failure(MqttNotConfigured) }(result)

        assertThat(outcome).isEqualTo(Outcome.Failure(MqttNotConfigured))
    }

    @Test
    fun mqttNotConfiguredHasStableCode() {
        assertThat(MqttNotConfigured.code).isEqualTo("mqtt_not_configured")
    }
}
