package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.port.ForeignPublishResult
import ru.restofleet.shared.kernel.Outcome
import ru.restofleet.shared.kernel.UnexpectedError

class DebugForeignTopicPublisherTest {
    private val calls = mutableListOf<Triple<String, String, Int>>()

    private fun publisher(outcome: Outcome<Unit, ru.restofleet.shared.kernel.DomainError>) =
        DebugForeignTopicPublisher { topic, payload, qos ->
            calls += Triple(topic, payload.decodeToString(), qos)
            outcome
        }

    @Test
    fun publishesQosOneToTheForeignDeviceTelemetryTopic() = runTest {
        publisher(Outcome.Failure(MqttError.NotAuthorized)).publish()

        assertThat(calls.single().first).isEqualTo("rf/v1/dev/dev-emu-31/telemetry")
        assertThat(calls.single().third).isEqualTo(1)
    }

    @Test
    fun puback0x87IsReportedAsNotAuthorized() = runTest {
        val result = publisher(Outcome.Failure(MqttError.NotAuthorized)).publish()

        assertThat(result).isEqualTo(ForeignPublishResult(accepted = false, reason = "0x87 not_authorized"))
    }

    @Test
    fun acceptedPublicationIsAnAclProblem() = runTest {
        val result = publisher(Outcome.Success(Unit)).publish()

        assertThat(result.accepted).isTrue()
        assertThat(result.reason).contains("accepted")
    }

    @Test
    fun otherFailuresShowTheirCode() = runTest {
        assertThat(publisher(Outcome.Failure(MqttError.NotConnected)).publish())
            .isEqualTo(ForeignPublishResult(false, "not_connected"))
        assertThat(publisher(Outcome.Failure(UnexpectedError("x"))).publish().accepted).isFalse()
    }
}
