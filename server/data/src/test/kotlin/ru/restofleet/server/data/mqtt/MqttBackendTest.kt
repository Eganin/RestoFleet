package ru.restofleet.server.data.mqtt

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.data.ingest.IngestPipeline

/** Запуск MQTT-части без брокера: повторы первого подключения и переход к подпискам. */
@OptIn(ExperimentalCoroutinesApi::class)
class MqttBackendTest {
    private val client = mockk<HiveMqClient>(relaxUnitFun = true)
    private val pipeline = mockk<IngestPipeline>(relaxUnitFun = true) {
        coEvery { run() } coAnswers { awaitCancellation() }
    }

    @Test
    fun `connect is retried with backoff until the broker answers`() = runTest {
        var connected = false
        var attempts = 0
        every { client.isConnected } answers { connected }
        coEvery { client.connect() } coAnswers {
            attempts++
            if (attempts < 3) error("broker down") else connected = true
        }
        val backend = MqttBackend(client, pipeline)

        backend.start(backgroundScope)
        runCurrent()
        advanceTimeBy(1_000 + 2_000 + 1)
        runCurrent()

        assertThat(attempts).isEqualTo(3)
        assertThat(backend.isReady).isTrue()
        coVerify(exactly = 1) { client.subscribe(any()) }
    }

    @Test
    fun `an automatic reconnect that wins the race is used instead of connecting again`() = runTest {
        var connected = false
        var attempts = 0
        every { client.isConnected } answers { connected }
        coEvery { client.connect() } coAnswers {
            attempts++
            // Первый CONNECT не удался, но HiveMQ уже переподключился сам: повторный connect() — «already connected»
            connected = true
            error("MQTT client is already connected")
        }
        val backend = MqttBackend(client, pipeline)

        backend.start(backgroundScope)
        runCurrent()

        assertThat(attempts).isEqualTo(1)
        assertThat(backend.isReady).isTrue()
        coVerify(exactly = 1) { client.subscribe(any()) }
    }
}
