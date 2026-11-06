package ru.restofleet.agent.data.probes

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Есть ли активная сеть с выходом в интернет (без проверки validated: captive portal тоже «сеть есть»). */
class NetworkAvailability(private val context: Context) {
    fun isAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
