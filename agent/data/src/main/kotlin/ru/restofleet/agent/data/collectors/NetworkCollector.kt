package ru.restofleet.agent.data.collectors

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.agent.domain.model.NetworkState
import ru.restofleet.agent.domain.model.Reading

/** R4: тип сети, RSSI, validated активной сети. */
class NetworkCollector(
    private val context: Context,
    private val apiLevel: ApiLevel = ApiLevel.Device,
) {
    @Suppress("DEPRECATION") // WifiInfo.rssi из connectionInfo нужен только для API 26..28
    fun read(): Reading<NetworkState> = guarded {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        Reading.Available(
            NetworkReader.state(caps, apiLevel.current()) {
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.rssi
            },
        )
    }
}
