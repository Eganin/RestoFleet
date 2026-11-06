package ru.restofleet.agent.data.platform

import ru.restofleet.agent.domain.model.NetworkType
import ru.restofleet.agent.domain.model.Reading
import ru.restofleet.agent.domain.model.UnavailableReason

/** Сырые значения ConnectivityManager/WifiInfo -> домен. */
object NetworkMapping {
    /** NetworkCapabilities.TRANSPORT_CELLULAR */
    private const val TRANSPORT_CELLULAR = 0

    /** NetworkCapabilities.TRANSPORT_WIFI */
    private const val TRANSPORT_WIFI = 1

    /** NetworkCapabilities.TRANSPORT_ETHERNET */
    private const val TRANSPORT_ETHERNET = 3

    /** WifiManager.UNKNOWN_SSID */
    private const val UNKNOWN_SSID = "<unknown ssid>"

    /** WifiInfo.INVALID_RSSI */
    private const val INVALID_RSSI = -127

    private const val QUOTE = "\""
    private const val LOCATION_PERMISSION = "ACCESS_FINE_LOCATION"

    /** Приоритет: Wi-Fi, Ethernet, сотовая сеть; VPN и прочее без базового транспорта — OTHER. */
    fun type(transports: Set<Int>): NetworkType = when {
        TRANSPORT_WIFI in transports -> NetworkType.WIFI
        TRANSPORT_ETHERNET in transports -> NetworkType.ETHERNET
        TRANSPORT_CELLULAR in transports -> NetworkType.CELLULAR
        transports.isEmpty() -> NetworkType.NONE
        else -> NetworkType.OTHER
    }

    fun rssi(raw: Int): Int? = if (raw == INVALID_RSSI || raw == Int.MIN_VALUE) null else raw

    /** Без разрешения на геолокацию платформа отдаёт null или `<unknown ssid>`. */
    fun ssid(raw: String?): Reading<String> =
        if (raw == null || raw == UNKNOWN_SSID) {
            Reading.Unavailable(UnavailableReason.PermissionMissing(LOCATION_PERMISSION))
        } else {
            Reading.Available(raw.removeSurrounding(QUOTE))
        }
}
