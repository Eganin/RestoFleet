package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.tls.HeldCertificate
import org.junit.Test
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.kernel.Outcome

/**
 * V-4 (К, реш. 426): возврат сети прерывает паузу переподключения — и паузу ретрайного отказа первого подключения,
 * и ожидание автопереподключения потерянного соединения. Пауза `auth_failed` и уже имевшаяся сеть паузу не прерывают.
 */
class NetworkReturnTest {
    private val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("stand-ca").build()
    private val settings = BrokerSettings("10.0.2.2", 8883, DeviceId.parse("dev-emu-36")!!, "pw-1")
    private val trace = mutableListOf<String>()
    private val changes = CredentialChanges()
    private val factory = FakeLinkFactory(trace)
    private val network = MutableSharedFlow<Boolean>(replay = 1)
    private val log = mutableListOf<String>()

    private val store = object : CredentialStore {
        override suspend fun save(s: BrokerSettings) = Unit

        override suspend fun load(): BrokerSettings = settings
    }

    private fun link(connId: String, result: Outcome<String, MqttError> = Outcome.Success(connId)) =
        FakeLink(connId, result, trace).also { factory.queue += it }

    private fun TestScope.session(available: () -> Flow<Boolean> = { network }) =
        MqttBrokerSession(
            credentials = store,
            caSource = { ca.certificatePem().toByteArray() },
            changes = changes,
            linkFactory = factory,
            ids = IdGenerator { "k-x" },
            clock = Clock { 1_000 },
            statusPayloads = StatusPayloads(DeviceFacts("m", "Google", "16", 36), "0.1.0", { false }, { "boot-1" }),
            scope = backgroundScope,
            log = { log += it },
            networkAvailable = available,
        )

    private fun connects() = trace.filter { it.startsWith("connect:") }

    @Test
    fun networkReturnCutsTheRetryPauseShort() = runTest {
        network.tryEmit(false)
        link("k-1", Outcome.Failure(MqttError.Failed("ConnectException")))
        link("k-2", Outcome.Failure(MqttError.Failed("ConnectException")))
        link("k-3")
        val s = session()
        s.connect()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertThat(connects()).containsExactly("connect:k-1", "connect:k-2").inOrder()
        val pauseStarted = currentTime

        network.tryEmit(true)
        runCurrent()

        // Вторая пауза — 2 с; сеть вернулась — подключение сразу
        assertThat(connects()).containsExactly("connect:k-1", "connect:k-2", "connect:k-3").inOrder()
        assertThat(currentTime).isEqualTo(pauseStarted)
        assertThat(s.state.value).isEqualTo(SessionState.CONNECTED)
    }

    @Test
    fun networkThatIsAlreadyUpDoesNotCutThePause() = runTest {
        network.tryEmit(true)
        link("k-1", Outcome.Failure(MqttError.TlsError))
        link("k-2")
        session().connect()
        runCurrent()

        network.tryEmit(true)
        runCurrent()
        assertThat(connects()).containsExactly("connect:k-1")

        advanceTimeBy(1_001)
        runCurrent()
        assertThat(connects()).containsExactly("connect:k-1", "connect:k-2").inOrder()
    }

    @Test
    fun authFailurePauseIsNotCutByNetwork() = runTest {
        network.tryEmit(false)
        link("k-1", Outcome.Failure(MqttError.AuthFailed))
        session().connect()
        runCurrent()

        network.tryEmit(true)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertThat(connects()).containsExactly("connect:k-1")
    }

    @Test
    fun lostConnectionReconnectsAsSoonAsNetworkIsBackWithoutOffline() = runTest {
        network.tryEmit(true)
        val first = link("k-1")
        val second = link("k-2")
        val s = session()
        s.connect()
        runCurrent()

        network.tryEmit(false)
        first.events.trySend(LinkEvent.Lost(MqttError.Failed("IOException")))
        runCurrent()
        assertThat(s.state.value).isEqualTo(SessionState.CONNECTING)
        network.tryEmit(true)
        runCurrent()

        assertThat(trace).containsAtLeast("close:k-1", "connect:k-2").inOrder()
        // Связи не было: offline не публикуется, сработает will старого подключения
        assertThat(first.published).hasSize(1)
        assertThat(second.published).hasSize(1)
        assertThat(s.state.value).isEqualTo(SessionState.CONNECTED)
        assertThat(log).contains("network is back: reconnecting now")
    }

    @Test
    fun networkFlapWhileConnectedKeepsTheConnection() = runTest {
        network.tryEmit(true)
        val only = link("k-1")
        val s = session()
        s.connect()
        runCurrent()

        network.tryEmit(false)
        runCurrent()
        network.tryEmit(true)
        runCurrent()

        assertThat(only.closed).isFalse()
        assertThat(s.state.value).isEqualTo(SessionState.CONNECTED)
    }

    @Test
    fun brokenNetworkObserverDoesNotStopTheSession() = runTest {
        link("k-1")
        val s = session { flow { throw IllegalStateException("callback") } }
        s.connect()
        runCurrent()

        assertThat(s.state.value).isEqualTo(SessionState.CONNECTED)
        assertThat(log).contains("network observer failed: IllegalStateException")
    }

    @Test
    fun returnsCountOnlyTransitionsFromNoNetwork() {
        val returns = NetworkReturns()
        returns.onNetwork(true)
        returns.onNetwork(true)
        assertThat(returns.current()).isEqualTo(1)
        returns.onNetwork(false)
        returns.onNetwork(false)
        returns.onNetwork(true)
        assertThat(returns.current()).isEqualTo(2)
    }
}
