package ru.restofleet.server.simulator.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.server.simulator.domain.device.VirtualDevice
import ru.restofleet.server.simulator.domain.fault.DeviceFault
import ru.restofleet.server.simulator.domain.fault.DeviceFaultType
import ru.restofleet.server.simulator.domain.fault.FaultEffects
import ru.restofleet.server.simulator.domain.fault.FaultPlan
import ru.restofleet.server.simulator.domain.fault.ForegroundTracker
import ru.restofleet.server.simulator.domain.model.DeviceConfig
import ru.restofleet.server.simulator.domain.model.SimEvent
import ru.restofleet.server.simulator.domain.model.TelemetrySample
import ru.restofleet.server.simulator.domain.profile.DayClock
import ru.restofleet.server.simulator.domain.profile.HallProfile
import ru.restofleet.server.simulator.domain.profile.KitchenProfile
import ru.restofleet.server.simulator.domain.synthetic.EpisodeGenerator
import ru.restofleet.server.simulator.domain.synthetic.ExplicitTruth
import ru.restofleet.server.simulator.domain.synthetic.NetworkVariant
import ru.restofleet.server.simulator.domain.synthetic.Outage
import ru.restofleet.server.simulator.domain.synthetic.OutageKind
import ru.restofleet.server.simulator.domain.synthetic.ProbeMode
import ru.restofleet.server.simulator.domain.synthetic.SyntheticNetwork
import java.time.ZoneId
import kotlin.random.Random

/** Отказы устройств (FR-SM-04), синтетическая сеть и генератор эпизодов (FR-SM-05, реш. 428), R-22. */
class FaultsAndSyntheticTest {
    private val start = 1_793_865_600_000L
    private val clock = DayClock(ZoneId.of("UTC"), 1.0, start)

    private fun sample(ts: Long = start) = TelemetrySample(
        ts, 1, 80, true, "ac", 31.0, "NONE", -60, 5_000, 1_000, 10, "com.android.chrome",
    )

    @Test
    fun effectsOverrideTelemetryOnlyWhileActive() {
        val all = DeviceFaultType.entries.toSet()
        val faulty = FaultEffects.apply(sample(), all)
        assertThat(faulty.batteryTempC).isEqualTo(FaultEffects.OVERHEAT_TEMP_C)
        assertThat(faulty.thermalStatus).isEqualTo("SEVERE")
        assertThat(faulty.batteryLevel).isEqualTo(FaultEffects.DISCHARGED_PCT)
        assertThat(faulty.charging).isFalse()
        assertThat(faulty.plugged).isEqualTo("none")
        assertThat(faulty.freeMb).isEqualTo(FaultEffects.LOW_FREE_MB)
        assertThat(faulty.foregroundApp).isEqualTo(FaultEffects.OTHER_APP)
        assertThat(FaultEffects.apply(sample(), emptySet())).isEqualTo(sample())
    }

    @Test
    fun planSelectsDevicesAndWindowDeterministically() {
        val fault = DeviceFault(DeviceFaultType.OVERHEAT, atSec = 10, durationSec = 5, devices = 2, offset = 1)
        val plan = { index: Int -> FaultPlan(listOf(fault), index, start) }
        assertThat(plan(0).activeAt(start + 12_000)).isEmpty()
        assertThat(plan(1).activeAt(start + 12_000)).containsExactly(DeviceFaultType.OVERHEAT)
        assertThat(plan(2).activeAt(start + 9_999)).isEmpty()
        assertThat(plan(2).activeAt(start + 15_000)).isEmpty()
        assertThat(plan(3).activeAt(start + 12_000)).isEmpty()
        assertThat(DeviceFaultType.fromCode("app_closed")).isEqualTo(DeviceFaultType.APP_CLOSED)
        assertThat(runCatching { DeviceFault(DeviceFaultType.OVERHEAT, -1, 1) }.isFailure).isTrue()
    }

    @Test
    fun overheatDischargeAndLowStorageRaiseThresholdEvents() {
        val faults = listOf(
            DeviceFault(DeviceFaultType.OVERHEAT, atSec = 60, durationSec = 60),
            DeviceFault(DeviceFaultType.DISCHARGE, atSec = 60, durationSec = 60),
            DeviceFault(DeviceFaultType.LOW_STORAGE, atSec = 60, durationSec = 60),
        )
        val plan = FaultPlan(faults, 0, start)
        val device = VirtualDevice(HallProfile, clock, Random(1), start, DeviceConfig(), faults = plan)
        val before = device.tick(start + 30_000)
        val during = device.tick(start + 90_000)
        val after = device.tick(start + 150_000)

        assertThat(before.events).isEmpty()
        assertThat(during.sample.freeMb).isEqualTo(FaultEffects.LOW_FREE_MB)
        val thresholds = during.events.filterIsInstance<SimEvent.Threshold>()
        val entered = thresholds.filter { it.state == "enter" }.map { it.metric }
        assertThat(entered).containsExactly("battery_low", "temp_high", "thermal")
        val exited = after.events.filterIsInstance<SimEvent.Threshold>()
            .filter { it.state == "exit" }.map { it.metric }
        assertThat(exited).containsAtLeast("temp_high", "thermal")
    }

    @Test
    fun appClosedEmitsChangedThenAbsentAfterLimitAndChangedBack() {
        val tracker = ForegroundTracker("com.android.chrome")
        assertThat(tracker.evaluate("com.android.chrome", start, 10)).isEmpty()
        val changed = tracker.evaluate("com.android.settings", start + 1_000, 10)
        assertThat(changed)
            .containsExactly(SimEvent.FgChanged(start + 1_000, "com.android.chrome", "com.android.settings"))
        assertThat(tracker.evaluate("com.android.settings", start + 300_000, 10)).isEmpty()
        val absent = tracker.evaluate("com.android.settings", start + 601_000, 10)
        assertThat(absent).containsExactly(
            SimEvent.FgAbsent(start + 601_000, "com.android.chrome", start + 1_000, 10, "com.android.settings"),
        )
        assertThat(tracker.evaluate("com.android.settings", start + 700_000, 10)).isEmpty()
        assertThat(tracker.evaluate("com.android.chrome", start + 701_000, 10).single())
            .isEqualTo(SimEvent.FgChanged(start + 701_000, "com.android.settings", "com.android.chrome"))
        assertThat(ForegroundTracker(null).evaluate("x", start, 1)).isEmpty()
    }

    @Test
    fun freeStorageShrinksAtRealTimeScale() {
        // R-22: при timeScale = 1 убыль за 30-секундный тик меньше 1 МБ — теперь она накапливается
        val device = VirtualDevice(KitchenProfile, clock, Random(3), start)
        val first = device.tick(start).sample.freeMb
        var last = first
        (1..480).forEach { last = device.tick(start + it * 30_000L).sample.freeMb }
        assertThat(last).isLessThan(first)
    }

    @Test
    fun syntheticProbesFollowOutages() {
        val outages = listOf(
            Outage(OutageKind.BACKEND_LOCAL_DOWN, 0, 10_000, restaurants = setOf("S8-02")),
            Outage(
                OutageKind.RESTAURANT_NETWORK_DOWN, 0, 10_000, restaurants = setOf("S8-03"),
                variant = NetworkVariant.REF_DOWN,
            ),
            Outage(OutageKind.RESTAURANT_NETWORK_DOWN, 0, 10_000, restaurants = setOf("S8-04")),
            Outage(OutageKind.DEVICE_DOWN, 0, 10_000, devices = setOf("d-5")),
            Outage(OutageKind.DEGRADATION, 0, 10_000, restaurants = setOf("S8-06"), latencyMs = 2_500),
            Outage(OutageKind.BACKEND_GLOBAL_DOWN, 20_000, 30_000),
        )
        val net = SyntheticNetwork(outages, start)
        val at = start + 5_000
        assertThat(net.probe("d-1", "S8-01", "api", at).ok).isTrue()
        assertThat(net.probe("d-2", "S8-02", "api", at).error).isEqualTo("timeout")
        assertThat(net.probe("d-2", "S8-02", "ref", at).ok).isTrue()
        assertThat(net.probe("d-3", "S8-03", "ref", at).ok).isFalse()
        assertThat(net.offline("d-3", "S8-03", at)).isFalse()
        assertThat(net.offline("d-4", "S8-04", at)).isTrue()
        assertThat(net.offline("d-5", "S8-05", at)).isTrue()
        assertThat(net.offline("d-6", "S8-05", at)).isFalse()
        assertThat(net.probe("d-7", "S8-06", "api", at).totalMs).isEqualTo(2_500)
        assertThat(net.probe("d-2", "S8-02", "api", start + 10_000).ok).isTrue()
        assertThat(net.probe("d-1", "S8-01", "api", start + 25_000).ok).isFalse()
        assertThat(net.probe("d-1", "S8-01", "ref", start + 25_000).ok).isTrue()
        assertThat(ProbeMode.fromCode("synthetic")).isEqualTo(ProbeMode.SYNTHETIC)
        assertThat(OutageKind.fromCode("nope")).isNull()
        assertThat(NetworkVariant.fromCode("ref_down")).isEqualTo(NetworkVariant.REF_DOWN)
        assertThat(runCatching { Outage(OutageKind.DEVICE_DOWN, 10, 10) }.isFailure).isTrue()
    }

    @Test
    fun episodeGeneratorIsDeterministicAndRoundsAreWellFormed() {
        val devices = (1..12).associate { r -> "S8-%02d".format(r) to (1..6).map { "d-$r-$it" } }
        val plan = EpisodeGenerator(seed = 7).generate("acc", rounds = 8, devices = devices, startMillis = start)
        assertThat(EpisodeGenerator(seed = 7).generate("acc", 8, devices, start)).isEqualTo(plan)
        assertThat(EpisodeGenerator(seed = 8).generate("acc", 8, devices, start)).isNotEqualTo(plan)

        val rounds = plan.truth.groupBy { (it.from - start) / 300_000 }
        assertThat(rounds.keys).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L)
        rounds.values.forEach { rows ->
            assertThat(rows.map { it.restaurant }.toSet()).hasSize(12)
            val classes = rows.groupingBy { it.expectedClass }.eachCount()
            if ("backend_global" in classes) {
                assertThat(classes).containsExactly("backend_global", 12)
            } else {
                assertThat(classes).containsExactly("restaurant_network", 1, "backend_local", 2, "device", 1, "none", 8)
                assertThat(rows.single { it.expectedClass == "device" }.device).startsWith("d-")
            }
            rows.forEach { assertThat(it.to - it.from).isEqualTo(150_000) }
            assertThat((rows.first().from - start) % 300_000).isEqualTo(30_000)
        }
        assertThat(plan.outages.map { it.kind }.toSet()).contains(OutageKind.BACKEND_LOCAL_DOWN)
        val tooFew = devices.entries.take(4).associate { it.toPair() }
        assertThat(runCatching { EpisodeGenerator(1).generate("x", 1, tooFew, start) }.isFailure).isTrue()
        assertThat(runCatching { EpisodeGenerator(1, roundSec = 100, activeFromSec = 30, activeSec = 100) }.isFailure)
            .isTrue()
    }

    @Test
    fun explicitOutagesGiveOneTruthRowPerRestaurant() {
        val devices = mapOf("S8-01" to listOf("d-1", "d-2"), "S8-02" to listOf("d-3"))
        val truth = ExplicitTruth.of(
            "s8", listOf(
                Outage(OutageKind.BACKEND_GLOBAL_DOWN, 0, 1_000),
                Outage(OutageKind.BACKEND_LOCAL_DOWN, 0, 1_000, restaurants = setOf("S8-02")),
                Outage(OutageKind.DEVICE_DOWN, 0, 1_000, devices = setOf("d-2", "d-9")),
            ),
            devices, start,
        )
        assertThat(truth.map { "${it.expectedClass}:${it.restaurant}:${it.device}" }).containsExactly(
            "backend_global:S8-01:null", "backend_global:S8-02:null", "backend_local:S8-02:null", "device:S8-01:d-2",
        ).inOrder()
        assertThat(truth.all { it.from == start && it.to == start + 1_000 }).isTrue()
    }
}
