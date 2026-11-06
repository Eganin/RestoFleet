package ru.restofleet.agent.data.report

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.error.MqttNotConfigured
import ru.restofleet.shared.kernel.Outcome

class NotConfiguredCheckResultPublisherTest {
    @Test
    fun alwaysFailsWithMqttNotConfigured() = runTest {
        val result = CheckResult(CheckId.R1, CheckStatus.OK, emptyMap(), null, 1L)

        assertThat(NotConfiguredCheckResultPublisher().publish(result)).isEqualTo(Outcome.Failure(MqttNotConfigured))
    }
}
