@file:Suppress("MatchingDeclarationName") // имя файла фиксировано брифом, объявление — DeviceResources

package ru.restofleet.agent.domain.model

data class DeviceResources(
    val freeStorageMb: Long,
    val availMemoryMb: Long,
    val uptimeSec: Long,
    val osVersion: String,
    val apiLevel: Int,
    val model: String,
    val manufacturer: String,
)
