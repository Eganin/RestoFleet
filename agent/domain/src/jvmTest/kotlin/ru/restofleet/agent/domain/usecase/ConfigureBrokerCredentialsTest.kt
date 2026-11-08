package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.restofleet.agent.domain.error.BrokerConfigError
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome

class ConfigureBrokerCredentialsTest {
    private class RecordingStore(private val failure: Throwable? = null) : CredentialStore {
        val saved = mutableListOf<BrokerSettings>()

        override suspend fun save(s: BrokerSettings) {
            failure?.let { throw it }
            saved += s
        }

        override suspend fun load(): BrokerSettings? = saved.lastOrNull()
    }

    private fun run(
        store: CredentialStore,
        host: String? = "10.0.2.2",
        port: Int? = 8883,
        deviceId: String? = "dev-emu-36",
        password: String? = "pw-value",
    ) = runBlocking { ConfigureBrokerCredentials(store)(host, port, deviceId, password) }

    @Test
    fun validInputIsSavedWithDeviceIdAsUsername() {
        val store = RecordingStore()

        val result = run(store)

        assertThat(result).isEqualTo(Outcome.Success(Unit))
        assertThat(store.saved).containsExactly(
            BrokerSettings("10.0.2.2", 8883, DeviceId.parse("dev-emu-36")!!, "pw-value"),
        )
    }

    @Test
    fun hostNamesAndDottedAddressesAreAccepted() {
        for (host in listOf("mosquitto", "broker.example.com", "10.0.2.2", "a_b-c")) {
            assertThat(run(RecordingStore(), host = host)).isEqualTo(Outcome.Success(Unit))
        }
    }

    @Test
    fun invalidHostIsRejectedAndNothingSaved() {
        for (host in listOf(null, "", "  ", "a b", "tls://10.0.2.2", "10.0.2.2:8883", "host/path", " host")) {
            val store = RecordingStore()
            assertThat(run(store, host = host)).isEqualTo(Outcome.Failure(BrokerConfigError.InvalidHost))
            assertThat(store.saved).isEmpty()
        }
    }

    @Test
    fun portOutsideRangeIsRejectedAndNothingSaved() {
        for (port in listOf(null, 0, -1, 65_536, Int.MAX_VALUE)) {
            val store = RecordingStore()
            assertThat(run(store, port = port)).isEqualTo(Outcome.Failure(BrokerConfigError.InvalidPort))
            assertThat(store.saved).isEmpty()
        }
    }

    @Test
    fun portBoundsAreAccepted() {
        for (port in listOf(1, 65_535)) {
            assertThat(run(RecordingStore(), port = port)).isEqualTo(Outcome.Success(Unit))
        }
    }

    @Test
    fun unsafeDeviceIdIsRejectedAndNothingSaved() {
        val tooLong = "a".repeat(65)
        for (id in listOf(null, "", "a/b", "a+b", "a#b", "a b", "дом", tooLong)) {
            val store = RecordingStore()
            assertThat(run(store, deviceId = id)).isEqualTo(Outcome.Failure(BrokerConfigError.InvalidDeviceId))
            assertThat(store.saved).isEmpty()
        }
    }

    @Test
    fun emptyPasswordIsRejectedAndNothingSaved() {
        for (pw in listOf(null, "")) {
            val store = RecordingStore()
            assertThat(run(store, password = pw)).isEqualTo(Outcome.Failure(BrokerConfigError.EmptyPassword))
            assertThat(store.saved).isEmpty()
        }
    }

    @Test
    fun storageFailureBecomesStorageFailedWithoutLeakingValues() {
        val result = run(RecordingStore(failure = IllegalStateException("boom pw-value")))

        assertThat(result).isEqualTo(Outcome.Failure(BrokerConfigError.StorageFailed))
    }

    @Test
    fun errorIsNotSwallowed() {
        assertThrows(OutOfMemoryError::class.java) { run(RecordingStore(failure = OutOfMemoryError())) }
    }

    @Test
    fun foreignCancellationWhileCallerIsActiveIsStorageFailure() {
        val result = run(RecordingStore(failure = CancellationException("foreign")))

        assertThat(result).isEqualTo(Outcome.Failure(BrokerConfigError.StorageFailed))
    }

    @Test
    fun cancellationOfCallerIsRethrown() {
        val store = object : CredentialStore {
            override suspend fun save(s: BrokerSettings) {
                currentCoroutineContext().job.cancel()
                throw CancellationException("cancelled")
            }

            override suspend fun load(): BrokerSettings? = null
        }
        var caught: Throwable? = null

        runBlocking {
            launch {
                caught = runCatching {
                    ConfigureBrokerCredentials(store)("10.0.2.2", 8883, "dev-emu-36", "pw")
                }.exceptionOrNull()
            }.join()
        }

        assertThat(caught).isInstanceOf(CancellationException::class.java)
    }
}
