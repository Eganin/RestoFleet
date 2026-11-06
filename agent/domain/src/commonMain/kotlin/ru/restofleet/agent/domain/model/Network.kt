package ru.restofleet.agent.domain.model

enum class NetworkType { WIFI, CELLULAR, ETHERNET, NONE, OTHER }

data class NetworkState(val type: NetworkType, val rssiDbm: Int?, val validated: Boolean)
