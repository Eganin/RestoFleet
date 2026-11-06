package ru.restofleet.agent.data.collectors

import android.content.Intent
import android.net.NetworkCapabilities
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.util.ReflectionHelpers
import ru.restofleet.agent.domain.model.NetworkState
import ru.restofleet.agent.domain.model.NetworkType
import ru.restofleet.agent.domain.model.PowerSource
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SimpleCollectorsTest {
    @Test
    fun batteryWithoutBroadcastIsFailed() {
        val reading = BatteryCollector(appContext).read()
        assertThat(reading).isInstanceOf(Reading::class.java)
    }

    @Test
    fun batteryFromStickyBroadcast() {
        appContext.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra("level", 50).putExtra("scale", 100).putExtra("status", 2)
                .putExtra("plugged", 2).putExtra("temperature", 301),
        )
        val value = (BatteryCollector(appContext).read() as Reading.Available).value
        assertThat(value.levelPct).isEqualTo(50)
        assertThat(value.charging).isTrue()
        assertThat(value.source).isEqualTo(PowerSource.USB)
        assertThat(value.tempC).isEqualTo(30.1)
    }

    @Test
    fun resourcesAreAvailable() {
        val value = (ResourceCollector(appContext).read() as Reading.Available).value
        assertThat(value.apiLevel).isEqualTo(30)
    }

    @Test
    fun ssidReadingDoesNotThrow() {
        assertThat(SsidCollector(appContext).read()).isInstanceOf(Reading::class.java)
    }

    @Test
    fun networkCollectorReadsActiveNetwork() {
        val reading = NetworkCollector(appContext) { 30 }.read()
        assertThat(reading).isInstanceOf(Reading.Available::class.java)
    }

    @Test
    fun securityExceptionBecomesPermissionMissing() {
        val reading = guarded<Int> { throw SecurityException("no perm") }
        assertThat(reading).isEqualTo(Reading.Unavailable(UnavailableReason.PermissionMissing("no perm")))
    }

    @Test
    fun securityExceptionWithoutMessageIsUnknown() {
        val reading = guarded<Int> { throw SecurityException() }
        assertThat(reading).isEqualTo(Reading.Unavailable(UnavailableReason.PermissionMissing("unknown")))
    }

    @Test
    fun otherExceptionBecomesFailed() {
        val reading = guarded<Int> { error("boom") }
        assertThat(reading).isEqualTo(Reading.Unavailable(UnavailableReason.Failed("boom")))
    }

    @Test
    fun exceptionWithoutMessageUsesClassName() {
        val reading = guarded<Int> { throw IllegalStateException() }
        assertThat(reading).isEqualTo(Reading.Unavailable(UnavailableReason.Failed("IllegalStateException")))
    }

    @Test(expected = kotlin.coroutines.cancellation.CancellationException::class)
    fun cancellationIsRethrown() {
        guarded<Int> { throw kotlin.coroutines.cancellation.CancellationException("x") }
    }

    private fun caps(vararg transports: Int, validated: Boolean = true, rssi: Int = -55): NetworkCapabilities {
        val caps = ShadowNetworkCapabilities.newInstance()
        val shadow = shadowOf(caps)
        transports.forEach { shadow.addTransportType(it) }
        if (validated) shadow.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ReflectionHelpers.callInstanceMethod<Any>(
            caps,
            "setSignalStrength",
            ReflectionHelpers.ClassParameter.from(Int::class.java, rssi),
        )
        return caps
    }

    @Test
    fun nullCapabilitiesIsNone() {
        assertThat(NetworkReader.state(null, 30) { 0 }).isEqualTo(NetworkReader.NONE)
    }

    @Test
    fun wifiOnApi29UsesSignalStrength() {
        val state = NetworkReader.state(caps(NetworkCapabilities.TRANSPORT_WIFI), 29) { error("not used") }
        assertThat(state).isEqualTo(NetworkState(NetworkType.WIFI, -55, true))
    }

    @Test
    fun wifiOnApi26UsesWifiManagerRssi() {
        val state = NetworkReader.state(caps(NetworkCapabilities.TRANSPORT_WIFI, validated = false), 26) { -70 }
        assertThat(state).isEqualTo(NetworkState(NetworkType.WIFI, -70, false))
    }

    @Test
    fun cellularHasNoRssi() {
        val state = NetworkReader.state(caps(NetworkCapabilities.TRANSPORT_CELLULAR), 30) { 0 }
        assertThat(state.type).isEqualTo(NetworkType.CELLULAR)
        assertThat(state.rssiDbm as Any?).isNull()
    }
}
