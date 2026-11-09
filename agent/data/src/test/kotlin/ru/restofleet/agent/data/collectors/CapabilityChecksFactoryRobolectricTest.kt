package ru.restofleet.agent.data.collectors

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.agent.data.deviceadmin.DeviceOwnerRole
import ru.restofleet.agent.data.deviceadmin.HardwarePropertiesCollector
import ru.restofleet.agent.data.deviceadmin.SerialCollector
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.agent.data.platform.FreeFallDetector
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.shared.kernel.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CapabilityChecksFactoryRobolectricTest {
    @Test
    fun createsChecksForAllReadingsWithWatchedPackages() = runBlocking {
        val api = ApiLevel { 30 }
        val clock = Clock { 1L }
        val job = Job()
        val checks = CapabilityChecksFactory(
            clock = clock,
            battery = BatteryCollector(appContext),
            thermal = ThermalCollector(appContext, api, "samsung/x:14", "qcom"),
            network = NetworkCollector(appContext, api),
            ssid = SsidCollector(appContext),
            resources = ResourceCollector(appContext),
            appVersions = AppVersionsCollector(appContext),
            foreground = ForegroundAppCollector(appContext, clock, apiLevel = api),
            light = LightSensorCollector(appContext, 10L),
            motion = MotionCollector(appContext, FreeFallDetector()),
            packageEvents = PackageEventsObserver(appContext),
            networkChanges = NetworkChangeObserver(appContext, api),
            hardware = HardwarePropertiesCollector(appContext, role = DeviceOwnerRole { false }),
            serial = SerialCollector(role = DeviceOwnerRole { false }) { "SN1234567890" },
            scope = CoroutineScope(job + Dispatchers.Default),
            watched = listOf(appContext.packageName, "com.missing"),
        ).create()
        assertThat(checks.map { it.id }).containsExactly(
            CheckId.R1, CheckId.R2, CheckId.R3, CheckId.R4, CheckId.R5, CheckId.R6, CheckId.R7,
            CheckId.R8, CheckId.R9, CheckId.R10, CheckId.R11, CheckId.R12, CheckId.R13, CheckId.R14,
        ).inOrder()
        val r7 = checks.first { it.id == CheckId.R7 }.run()
        assertThat(r7.status).isEqualTo(CheckStatus.OK)
        assertThat(r7.raw["com.missing"]).isEqualTo("not installed")
        val r11 = checks.first { it.id == CheckId.R11 }.run()
        assertThat(r11.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(r11.note).contains("missing sensor")
        val r6 = checks.first { it.id == CheckId.R6 }.run()
        assertThat(r6.status).isEqualTo(CheckStatus.OK)
        assertThat(r6.raw["api"]).isEqualTo("30")
        val r10 = checks.first { it.id == CheckId.R10 }.run()
        assertThat(r10.status).isEqualTo(CheckStatus.NOT_APPLICABLE)
        assertThat(r10.note).isEqualTo("RequiresDeviceOwner")
        val r13 = checks.first { it.id == CheckId.R13 }.run()
        assertThat(r13.status).isEqualTo(CheckStatus.OK)
        assertThat(r13.raw).containsExactly("serial", "****7890")
        assertThat(r13.toString()).doesNotContain("SN1234567890")
        job.cancel()
    }
}
