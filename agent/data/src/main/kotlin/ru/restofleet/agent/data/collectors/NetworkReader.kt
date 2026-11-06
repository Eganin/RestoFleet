package ru.restofleet.agent.data.collectors

import android.annotation.SuppressLint
import android.net.NetworkCapabilities
import ru.restofleet.agent.data.platform.NetworkMapping
import ru.restofleet.agent.domain.model.NetworkState
import ru.restofleet.agent.domain.model.NetworkType

/** Общая сборка [NetworkState] из [NetworkCapabilities] для R4 и R14. */
internal object NetworkReader {
    private val TRANSPORTS = listOf(
        NetworkCapabilities.TRANSPORT_CELLULAR,
        NetworkCapabilities.TRANSPORT_WIFI,
        NetworkCapabilities.TRANSPORT_BLUETOOTH,
        NetworkCapabilities.TRANSPORT_ETHERNET,
        NetworkCapabilities.TRANSPORT_VPN,
    )

    val NONE = NetworkState(NetworkType.NONE, null, false)

    /** [wifiRssi] — запасной RSSI для API < 29, где `signalStrength` недоступен. */
    @SuppressLint("NewApi") // signalStrength только при apiLevel >= 29
    fun state(caps: NetworkCapabilities?, apiLevel: Int, wifiRssi: () -> Int): NetworkState {
        if (caps == null) return NONE
        val type = NetworkMapping.type(TRANSPORTS.filterTo(mutableSetOf()) { caps.hasTransport(it) })
        val rssi = when {
            type != NetworkType.WIFI -> null
            apiLevel >= SIGNAL_STRENGTH_API -> NetworkMapping.rssi(caps.signalStrength)
            else -> NetworkMapping.rssi(wifiRssi())
        }
        return NetworkState(type, rssi, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }

    private const val SIGNAL_STRENGTH_API = 29
}
