package ru.restofleet.agent.data.probes

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import ru.restofleet.agent.data.collectors.appContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NetworkAvailabilityTest {
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun caps(vararg capabilities: Int): NetworkCapabilities {
        val c = Shadow.newInstanceOf(NetworkCapabilities::class.java)
        capabilities.forEach { shadowOf(c).addCapability(it) }
        return c
    }

    @Test
    fun trueWhenActiveNetworkHasInternet() {
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertThat(NetworkAvailability(appContext).isAvailable()).isTrue()
    }

    @Test
    fun falseWhenActiveNetworkLacksInternet() {
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        assertThat(NetworkAvailability(appContext).isAvailable()).isFalse()
    }

    @Test
    fun falseWithoutActiveNetwork() {
        shadowOf(cm).setActiveNetworkInfo(null)
        assertThat(NetworkAvailability(appContext).isAvailable()).isFalse()
    }
}
