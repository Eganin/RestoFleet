package ru.restofleet.server.simulator.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.server.simulator.domain.device.CommandAction
import ru.restofleet.server.simulator.domain.device.VirtualDevice
import ru.restofleet.server.simulator.domain.model.DesiredUpdate
import ru.restofleet.server.simulator.domain.model.DeviceConfig
import ru.restofleet.server.simulator.domain.model.IncomingCommand
import ru.restofleet.server.simulator.domain.model.ProbeResult
import ru.restofleet.server.simulator.domain.model.ProbeTarget
import ru.restofleet.server.simulator.domain.model.ReplyStatus
import ru.restofleet.server.simulator.domain.model.SimEvent
import ru.restofleet.server.simulator.domain.model.Thresholds
import ru.restofleet.server.simulator.domain.profile.DayClock
import ru.restofleet.server.simulator.domain.profile.HallProfile
import ru.restofleet.server.simulator.domain.profile.KitchenProfile
import java.time.ZoneId
import kotlin.random.Random

/** FR-SM-02: поведение виртуального устройства совпадает с агентом (тень, команды, пробы, пороги). */
class VirtualDeviceTest {
    private val start = 1_793_865_600_000L
    private val clock = DayClock(ZoneId.of("UTC"), 1.0, start)

    private fun device(config: DeviceConfig = DeviceConfig()) =
        VirtualDevice(HallProfile, clock, Random(1), start, config)

    private fun cmd(
        type: String,
        params: Map<String, String> = emptyMap(),
        expiresAt: Long? = null,
        id: String = "c-1",
    ) =
        IncomingCommand(id, type, params, expiresAt, receivedAtNanos = 5, correlationData = byteArrayOf(1, 2))

    private val api = ProbeTarget("api", "http", "https://api/health", 30, 1_000, failThreshold = 2)

    private fun desired(version: Long, interval: Int = 60, probes: List<ProbeTarget> = listOf(api)) =
        DesiredUpdate(version, interval, Thresholds(25, 44.0, 5), listOf("ru.app", "Bad Name"), probes)

    private fun done(action: CommandAction) = (action as CommandAction.Done).reply

    @Test
    fun tickProducesSequencedTelemetryWithProfileFields() {
        val device = device()
        val first = device.tick(start + 30_000).sample
        val second = device.tick(start + 60_000).sample
        assertThat(first.seq).isEqualTo(1)
        assertThat(second.seq).isEqualTo(2)
        assertThat(second.uptimeSec).isEqualTo(60)
        assertThat(second.foregroundApp).isEqualTo(HallProfile.foregroundApp)
        assertThat(second.plugged).isEqualTo(if (second.charging) "usb" else "none")
        assertThat(second.thermalStatus).isEqualTo("NONE")
    }

    @Test
    fun kitchenAtPeakCrossesTemperatureThreshold() {
        val noonUtc = start + 4 * 3_600_000L // start — 08:00 UTC
        val noon = DayClock(ZoneId.of("UTC"), 1.0, noonUtc)
        val config = DeviceConfig(thresholds = Thresholds(tempHighC = 36.0))
        val device = VirtualDevice(KitchenProfile, noon, Random(5), noonUtc, config)
        val events = (1..120).flatMap { device.tick(noonUtc + it * 30_000L).events }
        assertThat(events.filterIsInstance<SimEvent.Threshold>().map { it.metric to it.state })
            .contains("temp_high" to "enter")
    }

    @Test
    fun desiredIsAppliedByVersionWithValidation() {
        val device = device()
        val applied = device.applyDesired(desired(version = 2), now = start)!!
        assertThat(applied.appliedVersion).isEqualTo(2)
        assertThat(applied.errors).containsExactly("watchedApps:invalid_name:Bad Name")
        assertThat(device.config.telemetryIntervalSec).isEqualTo(60)
        assertThat(device.config.watchedApps).containsExactly("ru.app")
        assertThat(device.config.thresholds).isEqualTo(Thresholds(25, 44.0, 5))

        assertThat(device.applyDesired(desired(version = 1), now = start)).isNull()
        val echo = device.applyDesired(desired(version = 2), now = start + 1)!!
        assertThat(echo.errors).isEmpty()
        assertThat(echo.config).isEqualTo(device.config)

        val bad = device.applyDesired(desired(version = 3, interval = 5), now = start + 2)!!
        assertThat(bad.errors).contains("telemetryIntervalSec:out_of_range")
        assertThat(device.config.telemetryIntervalSec).isEqualTo(60)
        assertThat(device.config.version).isEqualTo(3)
    }

    @Test
    fun commandsAreAcceptedOnceWithReceivedFirst() {
        val device = device()
        val accepted = device.accept(cmd("ping"), now = start)!!
        assertThat(accepted.received.status).isEqualTo(ReplyStatus.RECEIVED)
        assertThat(accepted.received.correlationData).isEqualTo(byteArrayOf(1, 2))
        val reply = done(accepted.action)
        assertThat(reply.status).isEqualTo(ReplyStatus.SUCCEEDED)
        assertThat(reply.result).containsExactly("deviceTime", start.toString())
        assertThat(device.accept(cmd("ping"), now = start)).isNull()
    }

    @Test
    fun commandOutcomesFollowTheAgent() {
        val device = device()
        fun outcome(type: String, params: Map<String, String> = emptyMap(), expiresAt: Long? = null) =
            device.accept(cmd(type, params, expiresAt, id = "c-$type-${params.hashCode()}-$expiresAt"), start)!!
                .let { done(it.action) }
                .let { it.status to it.reason }

        assertThat(outcome("ping", expiresAt = start - 5 * 60_000 - 1)).isEqualTo(ReplyStatus.FAILED to "expired")
        assertThat(outcome("ping", expiresAt = start - 5 * 60_000)).isEqualTo(ReplyStatus.SUCCEEDED to null)
        assertThat(outcome("show_message")).isEqualTo(ReplyStatus.FAILED to "invalid_params:text")
        assertThat(outcome("show_message", mapOf("text" to "hi"))).isEqualTo(ReplyStatus.SUCCEEDED to null)
        assertThat(outcome("launch_app", mapOf("pkg" to "ru.app"))).isEqualTo(ReplyStatus.SUCCEEDED to null)
        assertThat(outcome("beep")).isEqualTo(ReplyStatus.UNSUPPORTED to "not_available")
        listOf("reboot", "install_update", "lock_task").forEach {
            assertThat(outcome(it)).isEqualTo(ReplyStatus.UNSUPPORTED to "requires_device_owner")
        }
        assertThat(outcome("teleport")).isEqualTo(ReplyStatus.UNSUPPORTED to "unknown_type:teleport")
        assertThat(outcome("run_probe")).isEqualTo(ReplyStatus.UNSUPPORTED to "invalid_target")
        assertThat(outcome("run_probe", mapOf("target" to "kds"))).isEqualTo(ReplyStatus.FAILED to "unknown_target")
    }

    @Test
    fun probeCommandsAndDiagnosticsUseConfiguredTargets() {
        val device = device()
        device.applyDesired(desired(version = 2), start)
        val probe = device.accept(cmd("run_probe", mapOf("target" to "api"), id = "p"), start)!!.action
        assertThat(probe).isEqualTo(CommandAction.RunProbe(api))
        val result = ProbeResult("api", ok = false, status = 503, error = "http_5xx", totalMs = 12)
        val reply = device.probeReply(cmd("run_probe", id = "p"), result, start)
        assertThat(reply.status).isEqualTo(ReplyStatus.SUCCEEDED)
        assertThat(reply.result).containsExactly(
            "target", "api", "ok", "false", "status", "503", "error", "http_5xx", "totalMs", "12",
        )

        val diagnostics = device.accept(cmd("collect_diagnostics", id = "d"), start)!!.action
        assertThat(diagnostics).isEqualTo(CommandAction.Diagnostics(listOf(api)))
        val ok = ProbeResult("api", true, 200, null, 5)
        val summary = device.diagnosticsReply(cmd("collect_diagnostics", id = "d"), 7, listOf(ok), start).result
        assertThat(summary).containsExactly(
            "seq", "7", "probes", "api", "probesOk", "api", "probesFailed", "", "unavailable", "",
        )
    }

    @Test
    fun probeStateMachineEmitsTransitionsAndScheduleLimitsRuns() {
        val device = device()
        device.applyDesired(desired(version = 2), start)
        assertThat(device.dueProbes(start)).containsExactly(api)
        assertThat(device.dueProbes(start + 29_999)).isEmpty()
        assertThat(device.dueProbes(start + 30_000)).containsExactly(api)

        val fail = ProbeResult("api", false, null, "timeout", 1_000)
        assertThat(device.recordProbe(api, fail, start)).isEmpty()
        val down = device.recordProbe(api, fail, start + 1).single() as SimEvent.ProbeState
        assertThat(down.state).isEqualTo("down")
        assertThat(down.failures).isEqualTo(2)
        assertThat(down.error).isEqualTo("timeout")
        val ok = ProbeResult("api", true, 200, null, 3)
        val up = device.recordProbe(api, ok, start + 2).single() as SimEvent.ProbeState
        assertThat(up.state).isEqualTo("up")
        assertThat(up.error).isNull()
        // Новый порог — новый автомат: первый успех снова даёт переход UNKNOWN → UP
        val stricter = api.copy(failThreshold = 5)
        device.applyDesired(desired(version = 3, probes = listOf(stricter)), start)
        assertThat(device.recordProbe(stricter, ProbeResult("api", true, 200, null, 3), start + 3)).hasSize(1)
    }

    @Test
    fun processedJournalIsBounded() {
        val device = device()
        (1..VirtualDevice.PROCESSED_LIMIT + 1).forEach { device.accept(cmd("ping", id = "c-$it"), start) }
        assertThat(device.accept(cmd("ping", id = "c-1"), start)).isNotNull()
        assertThat(device.accept(cmd("ping", id = "c-${VirtualDevice.PROCESSED_LIMIT + 1}"), start)).isNull()
    }
}
