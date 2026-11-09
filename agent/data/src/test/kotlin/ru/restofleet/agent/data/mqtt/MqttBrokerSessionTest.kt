package ru.restofleet.agent.data.mqtt

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.tls.HeldCertificate
import org.junit.Test
import ru.restofleet.agent.domain.command.Command
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.port.BrokerSettings
import ru.restofleet.agent.domain.port.CredentialStore
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.kernel.Outcome
import javax.net.ssl.TrustManagerFactory

internal class FakeLink(
    val connId: String,
    var connectResult: Outcome<String, MqttError> = Outcome.Success(connId),
    private val trace: MutableList<String>,
) : MqttLink {
    override val events = Channel<LinkEvent>(Channel.UNLIMITED)
    override val incoming = Channel<IncomingMessage>(Channel.UNLIMITED)
    val published = mutableListOf<OutboundMessage>()
    val subscriptions = mutableListOf<List<String>>()
    var closed = false
    var onPublish: (OutboundMessage) -> Unit = {}
    var publishResult: (OutboundMessage) -> Outcome<Unit, MqttError> = { Outcome.Success(Unit) }
    var subscribeResult: Outcome<Unit, MqttError> = Outcome.Success(Unit)

    override suspend fun connect(): Outcome<String, MqttError> {
        trace += "connect:$connId"
        return connectResult
    }

    override suspend fun subscribe(topics: List<String>): Outcome<Unit, MqttError> {
        trace += "subscribe:${topics.joinToString(",")}"
        subscriptions += topics
        return subscribeResult
    }

    override suspend fun publish(message: OutboundMessage): Outcome<Unit, MqttError> {
        trace += "publish:${message.topic}"
        onPublish(message)
        published += message
        return publishResult(message)
    }

    override suspend fun close() {
        trace += "close:$connId"
        closed = true
        events.close()
        incoming.close()
    }
}

internal class FakeLinkFactory(private val trace: MutableList<String>) : MqttLinkFactory {
    val queue = ArrayDeque<FakeLink>()
    val settingsSeen = mutableListOf<BrokerSettings>()
    val trustSeen = mutableListOf<TrustManagerFactory>()

    override fun create(settings: BrokerSettings, trust: TrustManagerFactory, configs: ConnectionConfigs): MqttLink {
        settingsSeen += settings
        trustSeen += trust
        return queue.removeFirst()
    }
}

class MqttBrokerSessionTest {
    private val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("stand-ca").build()
    private val caPem = ca.certificatePem().toByteArray()
    private val settings = BrokerSettings("10.0.2.2", 8883, DeviceId.parse("dev-emu-36")!!, "pw-1")
    private val trace = mutableListOf<String>()
    private val changes = CredentialChanges()
    private val factory = FakeLinkFactory(trace)

    private class MemoryStore(var value: BrokerSettings?) : CredentialStore {
        override suspend fun save(s: BrokerSettings) {
            value = s
        }

        override suspend fun load(): BrokerSettings? = value
    }

    private val memory = MemoryStore(settings)
    private val store = NotifyingCredentialStore(memory, changes)
    private var pem: ByteArray? = caPem

    private fun link(connId: String) = FakeLink(connId, trace = trace).also { factory.queue += it }

    private fun TestScope.session(): MqttBrokerSession {
        var n = 0
        return MqttBrokerSession(
            credentials = store,
            caSource = { pem },
            changes = changes,
            linkFactory = factory,
            ids = IdGenerator { "k-${++n}" },
            clock = Clock { 1_000 },
            statusPayloads = StatusPayloads(DeviceFacts("m", "Google", "16", 36), "0.1.0", { false }, { "boot-1" }),
            scope = backgroundScope,
        )
    }

    private fun text(m: OutboundMessage) = m.payload.decodeToString()

    @Test
    fun withoutCredentialsSessionDoesNotStartAndReportsNotConfigured() = runTest {
        memory.value = null
        val s = session()

        s.connect()
        runCurrent()

        assertThat(s.status.value.state).isEqualTo(SessionState.DISCONNECTED)
        assertThat(s.status.value.error).isEqualTo(MqttError.NotConfigured)
        assertThat(factory.settingsSeen).isEmpty()
    }

    @Test
    fun missingCaFailsClosedWithoutConnecting() = runTest {
        pem = null
        val s = session()

        s.connect()
        runCurrent()

        assertThat(s.status.value.error).isEqualTo(MqttError.CaMissing)
        assertThat(factory.settingsSeen).isEmpty()
    }

    @Test
    fun brokenCaFailsClosedWithoutConnecting() = runTest {
        pem = "garbage".toByteArray()
        val s = session()

        s.connect()
        runCurrent()

        assertThat(s.status.value.error).isEqualTo(MqttError.CaInvalid)
        assertThat(factory.settingsSeen).isEmpty()
    }

    @Test
    fun connectPublishesOnlineThenSubscribesThenBecomesConnected() = runTest {
        val l = link("k-1")
        val s = session()
        var stateAtOnline: SessionState? = null
        l.onPublish = { stateAtOnline = s.state.value }

        s.connect()
        runCurrent()

        assertThat(trace).containsExactly(
            "connect:k-1",
            "publish:rf/v1/dev/dev-emu-36/status",
            "subscribe:rf/v1/dev/dev-emu-36/shadow/desired,rf/v1/dev/dev-emu-36/cmd/req",
        ).inOrder()
        val online = l.published.single()
        assertThat(online.qos).isEqualTo(1)
        assertThat(online.retain).isTrue()
        assertThat(text(online)).contains("\"state\":\"online\"")
        assertThat(text(online)).contains("\"connId\":\"k-1\"")
        assertThat(stateAtOnline).isEqualTo(SessionState.CONNECTING)
        assertThat(s.state.value).isEqualTo(SessionState.CONNECTED)
        assertThat(s.status.value.connId).isEqualTo("k-1")
        assertThat(s.connectedEpoch.value).isEqualTo(1)
    }

    @Test
    fun sessionUsesTheStandCaOnly() = runTest {
        link("k-1")
        val s = session()

        s.connect()
        runCurrent()

        val trusted = factory.trustSeen.single().trustManagers
            .filterIsInstance<javax.net.ssl.X509TrustManager>().single().acceptedIssuers
        assertThat(trusted.map { it.subjectX500Principal.name }).containsExactly("CN=stand-ca")
    }

    @Test
    fun reconnectRepublishesOnlineWithTheNewConnIdAndResubscribes() = runTest {
        val l = link("k-1")
        val s = session()
        s.connect()
        runCurrent()

        l.events.trySend(LinkEvent.Lost(MqttError.Failed("IOException")))
        runCurrent()
        assertThat(s.state.value).isEqualTo(SessionState.CONNECTING)
        assertThat(s.status.value.error).isEqualTo(MqttError.Failed("IOException"))

        l.events.trySend(LinkEvent.Connected("k-2"))
        runCurrent()

        assertThat(text(l.published.last())).contains("\"connId\":\"k-2\"")
        assertThat(l.subscriptions).hasSize(2)
        assertThat(s.state.value).isEqualTo(SessionState.CONNECTED)
        assertThat(s.status.value.error).isNull()
        assertThat(s.connectedEpoch.value).isEqualTo(2)
    }

    @Test
    fun retryableConnectFailureIsRetriedWithBackoffAndFreshLink() = runTest {
        val first = link("k-1").also { it.connectResult = Outcome.Failure(MqttError.TlsError) }
        val second = link("k-2").also { it.connectResult = Outcome.Failure(MqttError.Failed("ConnectException")) }
        val third = link("k-3")
        val s = session()

        s.connect()
        runCurrent()
        assertThat(s.status.value.error).isEqualTo(MqttError.TlsError)
        assertThat(s.state.value).isEqualTo(SessionState.DISCONNECTED)
        assertThat(first.closed).isTrue()

        advanceTimeBy(999)
        runCurrent()
        assertThat(factory.settingsSeen).hasSize(1)
        advanceTimeBy(1)
        runCurrent()
        assertThat(factory.settingsSeen).hasSize(2)
        assertThat(second.closed).isTrue()

        advanceTimeBy(2_000)
        runCurrent()

        assertThat(factory.settingsSeen).hasSize(3)
        assertThat(s.state.value).isEqualTo(SessionState.CONNECTED)
        assertThat(third.published).hasSize(1)
    }

    @Test
    fun authFailureIsNotRetriedUntilCredentialsChange() = runTest {
        val bad = link("k-1").also { it.connectResult = Outcome.Failure(MqttError.AuthFailed) }
        val s = session()

        s.connect()
        runCurrent()
        advanceTimeBy(10 * 60_000)
        runCurrent()

        assertThat(s.status.value.error).isEqualTo(MqttError.AuthFailed)
        assertThat(factory.settingsSeen).hasSize(1)
        assertThat(bad.closed).isTrue()

        val good = link("k-2")
        store.save(settings.copy(password = "pw-2"))
        runCurrent()

        assertThat(factory.settingsSeen.map { it.password }).containsExactly("pw-1", "pw-2").inOrder()
        assertThat(s.state.value).isEqualTo(SessionState.CONNECTED)
        assertThat(good.published).hasSize(1)
    }

    @Test
    fun credentialChangeWhileConnectedReconnectsWithNewCredentials() = runTest {
        val old = link("k-1")
        val s = session()
        s.connect()
        runCurrent()

        val renewed = link("k-2")
        store.save(settings.copy(password = "pw-2"))
        runCurrent()

        assertThat(text(old.published.last())).contains("\"state\":\"offline\"")
        assertThat(text(old.published.last())).contains("\"reason\":\"reconfigure\"")
        assertThat(text(old.published.last())).contains("\"connId\":\"k-1\"")
        assertThat(old.closed).isTrue()
        assertThat(factory.settingsSeen.last().password).isEqualTo("pw-2")
        assertThat(renewed.published).hasSize(1)
        assertThat(s.state.value).isEqualTo(SessionState.CONNECTED)
    }

    @Test
    fun credentialsSavedWhileLoopIsBetweenStepsAreNotLost() = runTest {
        memory.value = null
        val s = session()
        s.connect()
        runCurrent()
        assertThat(s.status.value.error).isEqualTo(MqttError.NotConfigured)

        link("k-1")
        store.save(settings)
        runCurrent()

        assertThat(s.state.value).isEqualTo(SessionState.CONNECTED)
    }

    @Test
    fun disconnectSendsOfflineStopWithTheSameConnIdThenClosesLink() = runTest {
        val l = link("k-1")
        val s = session()
        s.connect()
        runCurrent()

        s.disconnect()

        val offline = l.published.last()
        assertThat(offline.topic).isEqualTo("rf/v1/dev/dev-emu-36/status")
        assertThat(offline.retain).isTrue()
        assertThat(offline.qos).isEqualTo(1)
        assertThat(text(offline)).contains("\"reason\":\"stop\"")
        assertThat(text(offline)).contains("\"connId\":\"k-1\"")
        assertThat(trace.takeLast(2)).containsExactly("publish:rf/v1/dev/dev-emu-36/status", "close:k-1").inOrder()
        assertThat(s.state.value).isEqualTo(SessionState.DISCONNECTED)
    }

    @Test
    fun publishMapsKindsToTopicsAndKeepsQosRetainAndCorrelation() = runTest {
        val l = link("k-1")
        val s = session()
        s.connect()
        runCurrent()
        l.published.clear()

        fun msg(kind: MessageKind, qos: Int, retain: Boolean = false, corr: ByteArray? = null, resp: String? = null) =
            OutgoingMessage(kind, "x".toByteArray(), qos, retain, corr, resp, 1)
        s.publish(msg(MessageKind.TELEMETRY, 0))
        s.publish(msg(MessageKind.PROBE, 0))
        s.publish(msg(MessageKind.EVENT, 1))
        s.publish(msg(MessageKind.SHADOW_REPORTED, 1))
        s.publish(msg(MessageKind.STATUS, 1, retain = true))
        s.publish(msg(MessageKind.COMMAND_RESULT, 1, corr = "c-1".toByteArray(), resp = "rf/v1/dev/dev-emu-36/cmd/res"))
        s.publish(msg(MessageKind.COMMAND_RESULT, 1, resp = "rf/v1/dev/dev-emu-31/cmd/res"))
        s.publish(msg(MessageKind.COMMAND_RESULT, 1, resp = "rf/v1/dev/dev-emu-36/status"))

        assertThat(l.published.map { it.topic }).containsExactly(
            "rf/v1/dev/dev-emu-36/telemetry",
            "rf/v1/dev/dev-emu-36/probe",
            "rf/v1/dev/dev-emu-36/events",
            "rf/v1/dev/dev-emu-36/shadow/reported",
            "rf/v1/dev/dev-emu-36/status",
            "rf/v1/dev/dev-emu-36/cmd/res",
            "rf/v1/dev/dev-emu-36/cmd/res",
            "rf/v1/dev/dev-emu-36/cmd/res",
        ).inOrder()
        assertThat(l.published.map { it.qos }.take(3)).containsExactly(0, 0, 1).inOrder()
        assertThat(l.published[4].retain).isTrue()
        assertThat(l.published[5].correlationData!!.decodeToString()).isEqualTo("c-1")
    }

    @Test
    fun publishWithoutConnectionFailsWithoutTouchingTheLink() = runTest {
        val l = link("k-1").also { it.connectResult = Outcome.Failure(MqttError.TlsError) }
        val s = session()
        s.connect()
        runCurrent()

        val result = s.publish(OutgoingMessage(MessageKind.EVENT, ByteArray(0), 1, createdAt = 1))

        assertThat((result as Outcome.Failure).error).isEqualTo(MqttError.NotConnected)
        assertThat(l.published).isEmpty()
    }

    @Test
    fun publishFailureFromLinkIsReturnedNotThrown() = runTest {
        val l = link("k-1")
        val s = session()
        s.connect()
        runCurrent()
        l.publishResult = { Outcome.Failure(MqttError.NotAuthorized) }

        val result = s.publish(OutgoingMessage(MessageKind.EVENT, ByteArray(0), 1, createdAt = 1))

        assertThat((result as Outcome.Failure).error).isEqualTo(MqttError.NotAuthorized)
    }

    private fun incoming(topic: String, body: String, acks: MutableList<String>, name: String = topic) =
        IncomingMessage(topic, body.toByteArray(), "rf/v1/dev/dev-emu-36/cmd/res", "c-1".toByteArray()) { acks += name }

    private val cmdBody = """{"cmdId":"c-1","type":"ping","params":{}}"""

    @Test
    fun commandIsDeliveredWithResponseTopicAndAckedOnlyAfterTheConsumerReturns() = runTest {
        val l = link("k-1")
        val s = session()
        s.connect()
        runCurrent()
        val acks = mutableListOf<String>()
        var acksWhileHandling = -1
        val got = mutableListOf<Command?>()
        backgroundScope.launch {
            s.commands.collect { cmd ->
                got += cmd.command
                assertThat(cmd.responseTopic).isEqualTo("rf/v1/dev/dev-emu-36/cmd/res")
                assertThat(cmd.correlationData!!.decodeToString()).isEqualTo("c-1")
                acksWhileHandling = acks.size
            }
        }

        l.incoming.trySend(incoming("rf/v1/dev/dev-emu-36/cmd/req", cmdBody, acks))
        runCurrent()

        assertThat(got).containsExactly(Command.Ping)
        assertThat(acksWhileHandling).isEqualTo(0)
        assertThat(acks).hasSize(1)
    }

    @Test
    fun commandsAreDeliveredOneAtATimeInOrder() = runTest {
        val l = link("k-1")
        val s = session()
        s.connect()
        runCurrent()
        val acks = mutableListOf<String>()
        val order = mutableListOf<String>()
        backgroundScope.launch {
            s.commands.collect { cmd ->
                order += "start:${cmd.cmdId}"
                kotlinx.coroutines.delay(100)
                order += "end:${cmd.cmdId}"
            }
        }

        listOf("c-1", "c-2").forEach {
            l.incoming.trySend(
                incoming("rf/v1/dev/dev-emu-36/cmd/req", """{"cmdId":"$it","type":"ping"}""", acks, name = it),
            )
        }
        advanceTimeBy(1_000)
        runCurrent()

        assertThat(order).containsExactly("start:c-1", "end:c-1", "start:c-2", "end:c-2").inOrder()
        assertThat(acks).containsExactly("c-1", "c-2").inOrder()
    }

    @Test
    fun consumerFailureLeavesCommandUnacked() = runTest {
        val l = link("k-1")
        val s = session()
        s.connect()
        runCurrent()
        val acks = mutableListOf<String>()
        backgroundScope.launch {
            runCatching { s.commands.collect { throw IllegalStateException("boom") } }
        }

        l.incoming.trySend(incoming("rf/v1/dev/dev-emu-36/cmd/req", cmdBody, acks))
        runCurrent()

        assertThat(acks).isEmpty()
    }

    @Test
    fun unparsableCommandIsAckedAndDropped() = runTest {
        val l = link("k-1")
        val s = session()
        s.connect()
        runCurrent()
        val acks = mutableListOf<String>()
        val got = mutableListOf<String>()
        backgroundScope.launch { s.commands.collect { got += it.cmdId } }

        l.incoming.trySend(incoming("rf/v1/dev/dev-emu-36/cmd/req", "{ not json", acks))
        runCurrent()

        assertThat(got).isEmpty()
        assertThat(acks).hasSize(1)
    }

    @Test
    fun desiredIsDeliveredAndAckedAfterTheConsumerReturns() = runTest {
        val l = link("k-1")
        val s = session()
        s.connect()
        runCurrent()
        val acks = mutableListOf<String>()
        var acksWhileHandling = -1
        var version = -1L
        backgroundScope.launch {
            s.desired.collect {
                version = it.version
                acksWhileHandling = acks.size
            }
        }
        val body = """{"version":7,"telemetry":{"intervalSec":45},""" +
            """"thresholds":{"batteryLowPct":20,"tempHighC":45.0,"fgAbsentMin":10}}"""

        l.incoming.trySend(incoming("rf/v1/dev/dev-emu-36/shadow/desired", body, acks))
        runCurrent()

        assertThat(version).isEqualTo(7)
        assertThat(acksWhileHandling).isEqualTo(0)
        assertThat(acks).hasSize(1)
    }

    @Test
    fun emptyRetainedDesiredAndForeignTopicsAreAckedAndIgnored() = runTest {
        val l = link("k-1")
        val s = session()
        s.connect()
        runCurrent()
        val acks = mutableListOf<String>()
        val got = mutableListOf<Long>()
        backgroundScope.launch { s.desired.collect { got += it.version } }

        l.incoming.trySend(incoming("rf/v1/dev/dev-emu-36/shadow/desired", "", acks))
        l.incoming.trySend(incoming("rf/v1/dev/dev-emu-31/cmd/req", cmdBody, acks))
        runCurrent()

        assertThat(got).isEmpty()
        assertThat(acks).hasSize(2)
    }

    @Test
    fun connectIsIdempotent() = runTest {
        link("k-1")
        val s = session()

        s.connect()
        s.connect()
        runCurrent()

        assertThat(factory.settingsSeen).hasSize(1)
        assertThat(s.state.first()).isEqualTo(SessionState.CONNECTED)
    }

    @Test
    fun publishToSendsToTheExplicitTopicWithTheGivenQos() = runTest {
        val l = link("k-1")
        val s = session()
        s.connect()
        runCurrent()

        val outcome = s.publishTo("rf/v1/dev/dev-emu-31/telemetry", "x".toByteArray(), 1)

        assertThat(outcome).isEqualTo(Outcome.Success(Unit))
        val sent = l.published.last()
        assertThat(sent.topic).isEqualTo("rf/v1/dev/dev-emu-31/telemetry")
        assertThat(sent.qos).isEqualTo(1)
        assertThat(sent.retain).isFalse()
        assertThat(text(sent)).isEqualTo("x")
    }

    @Test
    fun publishToPropagatesTheBrokerRefusal() = runTest {
        val l = link("k-1")
        val s = session()
        s.connect()
        runCurrent()
        l.publishResult = { Outcome.Failure(MqttError.NotAuthorized) }

        val outcome = s.publishTo("rf/v1/dev/dev-emu-31/telemetry", ByteArray(0), 1)

        assertThat(outcome).isEqualTo(Outcome.Failure(MqttError.NotAuthorized))
    }

    @Test
    fun publishToWithoutConnectionFailsWithNotConnected() = runTest {
        val s = session()

        val outcome = s.publishTo("rf/v1/dev/dev-emu-31/telemetry", ByteArray(0), 1)

        assertThat(outcome).isEqualTo(Outcome.Failure(MqttError.NotConnected))
    }
}
