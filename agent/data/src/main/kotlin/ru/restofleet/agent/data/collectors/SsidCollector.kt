package ru.restofleet.agent.data.collectors

import android.content.Context
import android.net.wifi.WifiManager
import ru.restofleet.agent.data.platform.NetworkMapping
import ru.restofleet.agent.domain.model.Reading

/** R5: SSID текущей сети; без ACCESS_FINE_LOCATION платформа отдаёт `<unknown ssid>`. */
class SsidCollector(private val context: Context) {
    @Suppress("DEPRECATION") // connectionInfo работает на всех API 26+, замена требует колбэка
    fun read(): Reading<String> = guarded {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        NetworkMapping.ssid(wifi.connectionInfo?.ssid)
    }
}
