package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.shared.kernel.Outcome
import java.io.IOException
import java.util.concurrent.CompletableFuture

/**
 * FR-AG-79 (долг К, реш. 326): публикация ждёт PUBACK не дольше 10 с. Future HiveMQ, который не завершается
 * (брокер не ответил), даёт `publish_timeout` ровно на 10-й секунде, а не висит.
 */
class PublishTimeoutTest {
    @Test
    fun publishTimeoutIsTenSeconds() {
        assertThat(PUBLISH_TIMEOUT_MS).isEqualTo(10_000L)
    }

    @Test
    fun unansweredPublishFailsWithTimeoutAfterTenSeconds() = runTest {
        val never = CompletableFuture<Unit>()
        val outcome = async { awaitPublish { never.await() } }

        advanceTimeBy(PUBLISH_TIMEOUT_MS - 1)
        runCurrent()
        assertThat(outcome.isCompleted).isFalse()

        advanceTimeBy(1)
        runCurrent()
        assertThat(outcome.await()).isEqualTo(Outcome.Failure(MqttError.Failed("publish_timeout")))
    }

    @Test
    fun answeredPublishSucceedsAndFailureIsMapped() = runTest {
        assertThat(awaitPublish { CompletableFuture.completedFuture(Unit).await() }).isEqualTo(Outcome.Success(Unit))

        val failed = awaitPublish { CompletableFuture.failedFuture<Unit>(IOException("reset")).await() }
        assertThat(failed).isInstanceOf(Outcome.Failure::class.java)
        assertThat(failed).isNotEqualTo(Outcome.Failure(MqttError.Failed("publish_timeout")))
    }
}
