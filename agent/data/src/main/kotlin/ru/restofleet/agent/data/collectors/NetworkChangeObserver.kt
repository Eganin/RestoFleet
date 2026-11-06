package ru.restofleet.agent.data.collectors

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.restofleet.agent.data.platform.ApiLevel
import ru.restofleet.agent.domain.model.NetworkState

/** R14: смена сети и потеря интернета через `registerDefaultNetworkCallback`. */
class NetworkChangeObserver(
    private val context: Context,
    private val apiLevel: ApiLevel = ApiLevel.Device,
) {
    @Suppress("DEPRECATION") // connectionInfo.rssi нужен только для API 26..28
    fun changes(): Flow<NetworkState> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            @Suppress("TooGenericExceptionCaught") // колбэк на потоке connectivity: сбой не должен ронять процесс
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                try {
                    trySend(
                        NetworkReader.state(caps, apiLevel.current()) {
                            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                                .connectionInfo.rssi
                        },
                    )
                } catch (e: Exception) {
                    close(e)
                }
            }

            override fun onLost(network: Network) {
                trySend(NetworkReader.NONE) // trySend не бросает: закрытый канал даёт failed-результат
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
