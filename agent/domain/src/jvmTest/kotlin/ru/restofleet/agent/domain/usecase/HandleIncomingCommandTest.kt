package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.AgentMode
import ru.restofleet.agent.domain.capability.Capabilities
import ru.restofleet.agent.domain.command.Command
import ru.restofleet.agent.domain.fake.CallLog
import ru.restofleet.agent.domain.fake.FakeAppLauncher
import ru.restofleet.agent.domain.fake.FakeBeeper
import ru.restofleet.agent.domain.fake.FakeBrokerSession
import ru.restofleet.agent.domain.fake.FakeDeviceAdmin
import ru.restofleet.agent.domain.fake.FakeMessagePublisher
import ru.restofleet.agent.domain.fake.FakeOutbox
import ru.restofleet.agent.domain.fake.FakePayloadEncoder
import ru.restofleet.agent.domain.fake.FakeProcessedCommands
import ru.restofleet.agent.domain.fake.FakeUserNotifier
import ru.restofleet.agent.domain.fake.TestError
import ru.restofleet.agent.domain.model.IncomingCommand
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.port.CapabilitiesSource
import ru.restofleet.agent.domain.port.SessionState
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.Outcome
import kotlin.test.assertFailsWith

@Suppress("LargeClass")
class HandleIncomingCommandTest {
    private val log = CallLog()
    private var caps = Capabilities(AgentMode.NORMAL, 34, emptySet())
    private val admin = FakeDeviceAdmin()
    private val beeper = FakeBeeper()
    private val session = FakeBrokerSession()
    private val publisher = FakeMessagePublisher(log)
    private val outbox = FakeOutbox(log)
    private val processed = FakeProcessedCommands(log)
    private val encoder = FakePayloadEncoder()
    private val now = 1_000L
    private val clock = Clock { now }
    private val execute = ExecuteCommand(
        caps = CapabilitiesSource { caps },
        admin = admin,
        notifier = FakeUserNotifier(),
        launcher = FakeAppLauncher(),
        beeper = beeper,
        clock = clock,
    )
    private val handle = HandleIncomingCommand(
        execute,
        processed,
        PublishOrBuffer(session, publisher, outbox),
        encoder,
        clock,
    )
    private val sha = "a".repeat(64)

    private fun deviceOwner() {
        caps = Capabilities(AgentMode.DEVICE_OWNER, 34, emptySet())
    }

    private fun incoming(
        command: Command? = Command.Ping,
        cmdId: String = "c1",
        rawType: String = "ping",
        expiresAt: Long? = null,
        parseError: String? = null,
        responseTopic: String? = "rf/v1/dev/d/cmd/res",
        correlationData: ByteArray? = cmdId.toByteArray(),
    ) = IncomingCommand(cmdId, command, rawType, responseTopic, correlationData, expiresAt, parseError)

    private fun sent() = publisher.published.map { String(it.payload) }

    private fun res(status: String, outcome: String, cmdId: String = "c1") = "res:$cmdId:$status:$outcome:$now"

    @Test
    fun receivedThenSucceededForPing() = runTest {
        handle(incoming())

        assertThat(sent()).containsExactly(
            res("received", "null"),
            res("succeeded", "Succeeded(result={deviceTime=1000})"),
        ).inOrder()
    }

    @Test
    fun replyCarriesRequestCorrelationAndResponseTopic() = runTest {
        handle(incoming())

        assertThat(publisher.published).hasSize(2)
        publisher.published.forEach {
            assertThat(it.kind).isEqualTo(MessageKind.COMMAND_RESULT)
            assertThat(it.qos).isEqualTo(1)
            assertThat(it.retain).isFalse()
            assertThat(it.responseTopic).isEqualTo("rf/v1/dev/d/cmd/res")
            assertThat(String(it.correlationData!!)).isEqualTo("c1")
            assertThat(it.createdAt).isEqualTo(now)
        }
    }

    @Test
    fun missingResponseTopicAndCorrelationStayNull() = runTest {
        handle(incoming(responseTopic = null, correlationData = null))

        publisher.published.forEach {
            assertThat(it.responseTopic).isNull()
            assertThat(it.correlationData).isNull()
        }
    }

    @Test
    fun duplicateCmdIdIsNotExecutedAndNothingIsPublished() = runTest {
        processed.ids += "c1"

        handle(incoming(Command.Beep(500), rawType = "beep"))

        assertThat(beeper.calls).isEmpty()
        assertThat(publisher.calls).isEqualTo(0)
        assertThat(outbox.messages).isEmpty()
    }

    @Test
    fun redeliveryAfterHandlingExecutesOnlyOnce() = runTest {
        val beep = incoming(Command.Beep(500), rawType = "beep")

        handle(beep)
        handle(beep)

        assertThat(beeper.calls).hasSize(1)
        assertThat(publisher.published).hasSize(2)
    }

    @Test
    fun expiredCommandFailsWithoutExecution() = runTest {
        handle(incoming(Command.Beep(500), rawType = "beep", expiresAt = now - 1))

        assertThat(beeper.calls).isEmpty()
        assertThat(sent()).containsExactly(
            res("received", "null"),
            res("failed", "Failed(reason=expired)"),
        ).inOrder()
        assertThat(processed.ids).contains("c1")
    }

    @Test
    fun commandExpiringExactlyNowIsStillExecuted() = runTest {
        handle(incoming(Command.Beep(500), rawType = "beep", expiresAt = now))

        assertThat(beeper.calls).hasSize(1)
    }

    @Test
    fun unknownTypeIsUnsupported() = runTest {
        handle(incoming(command = null, rawType = "teleport"))

        assertThat(sent()).containsExactly(
            res("received", "null"),
            res("unsupported", "Unsupported(reason=unknown_type:teleport)"),
        ).inOrder()
        assertThat(processed.ids).contains("c1")
    }

    @Test
    fun parseErrorFailsWithInvalidParams() = runTest {
        handle(incoming(command = null, rawType = "beep", parseError = "durationMs"))

        assertThat(sent()).containsExactly(
            res("received", "null"),
            res("failed", "Failed(reason=invalid_params:durationMs)"),
        ).inOrder()
        assertThat(processed.ids).contains("c1")
    }

    @Test
    fun unsupportedOutcomeKeepsItsReason() = runTest {
        handle(incoming(Command.Reboot, rawType = "reboot"))

        assertThat(sent().last()).isEqualTo(res("unsupported", "Unsupported(reason=requires_device_owner)"))
    }

    @Test
    fun failedOutcomeKeepsItsReason() = runTest {
        beeper.result = { Outcome.Failure(TestError("ringer_silent")) }

        handle(incoming(Command.Beep(500), rawType = "beep"))

        assertThat(sent().last()).isEqualTo(res("failed", "Failed(reason=ringer_silent)"))
    }

    @Test
    fun markProcessedHappensAfterReceivedAndBeforeExecution() = runTest {
        beeper.result = {
            log += "execute"
            Outcome.Success(emptyMap())
        }

        handle(incoming(Command.Beep(500), rawType = "beep"))

        assertThat(log.entries).containsExactly(
            "publish:${res("received", "null")}",
            "mark:c1",
            "execute",
            "publish:${res("succeeded", "Succeeded(result={})")}",
        ).inOrder()
    }

    @Test
    fun markProcessedPrecedesRebootEvenWhenReceivedIsBuffered() = runTest {
        deviceOwner()
        session.state.value = SessionState.DISCONNECTED
        admin.rebootResult = {
            log += "execute"
            Outcome.Success(Unit)
        }

        handle(incoming(Command.Reboot, rawType = "reboot"))

        assertThat(log.entries.take(3)).containsExactly(
            "enqueue:${res("received", "null")}",
            "mark:c1",
            "execute",
        ).inOrder()
    }

    @Test
    fun disconnectedSessionKeepsReceivedBeforeSucceededInOutbox() = runTest {
        session.state.value = SessionState.DISCONNECTED

        handle(incoming())

        assertThat(publisher.calls).isEqualTo(0)
        assertThat(outbox.messages).hasSize(2)
        assertThat(String(outbox.messages.first().payload)).contains(":received:")
        assertThat(String(outbox.messages.last().payload)).contains(":succeeded:")
    }

    @Test
    fun ordinaryCommandTimesOutAfterSixtySeconds() = runTest {
        beeper.result = {
            delay(Long.MAX_VALUE)
            Outcome.Success(emptyMap())
        }
        launch { handle(incoming(Command.Beep(500), rawType = "beep")) }

        advanceTimeBy(59_999)
        runCurrent()
        assertThat(sent()).containsExactly(res("received", "null"))

        advanceTimeBy(1)
        runCurrent()
        assertThat(sent()).containsExactly(
            res("received", "null"),
            res("failed", "Failed(reason=timeout)"),
        ).inOrder()
    }

    @Test
    fun installApkGetsTenMinutes() = runTest {
        deviceOwner()
        admin.installResult = {
            delay(Long.MAX_VALUE)
            Outcome.Success(Unit)
        }
        launch { handle(incoming(Command.InstallApk("https://apk-host/a.apk", sha), rawType = "install_update")) }

        advanceTimeBy(599_999)
        runCurrent()
        assertThat(sent()).containsExactly(res("received", "null"))

        advanceTimeBy(1)
        runCurrent()
        assertThat(sent().last()).isEqualTo(res("failed", "Failed(reason=timeout)"))
    }

    @Test
    fun outerCancellationPropagatesWithoutReply() = runTest {
        beeper.result = {
            delay(Long.MAX_VALUE)
            Outcome.Success(emptyMap())
        }
        val job: Job = launch(start = CoroutineStart.UNDISPATCHED) {
            handle(incoming(Command.Beep(500), rawType = "beep"))
        }
        runCurrent()

        job.cancel()
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(sent()).containsExactly(res("received", "null"))
    }

    @Test
    fun encoderFailureOnReceivedIsAnsweredWithFailedException() = runTest {
        encoder.commandResultFailure = { status -> if (status == "received") IllegalStateException("x") else null }

        handle(incoming(Command.Beep(500), rawType = "beep"))

        assertThat(sent()).containsExactly(res("failed", "Failed(reason=exception:IllegalStateException)"))
        assertThat(beeper.calls).isEmpty()
    }

    @Test
    fun strayCancellationFromEncoderIsFailureNotCancel() = runTest {
        encoder.commandResultFailure = { status -> if (status == "succeeded") CancellationException("stray") else null }

        handle(incoming())

        assertThat(sent().last()).isEqualTo(res("failed", "Failed(reason=exception:CancellationException)"))
    }

    @Test
    fun brokenEncoderNeverBringsHandlerDown() = runTest {
        encoder.commandResultFailure = { IllegalStateException("always") }

        handle(incoming())

        assertThat(publisher.calls).isEqualTo(0)
    }

    @Test
    fun errorFromEncoderPropagates() = runTest {
        encoder.commandResultFailure = { AssertionError("fatal") }

        assertFailsWith<AssertionError> { handle(incoming()) }
    }

    @Test
    fun exceptionInsideExecutionBecomesFailedException() = runTest {
        val broken = HandleIncomingCommand(
            ExecuteCommand({ error("caps") }, admin, FakeUserNotifier(), FakeAppLauncher(), beeper, clock),
            processed,
            PublishOrBuffer(session, publisher, outbox),
            encoder,
            clock,
        )

        broken(incoming())

        assertThat(sent().last()).isEqualTo(res("failed", "Failed(reason=exception:IllegalStateException)"))
    }
}
