package ru.restofleet.agent.domain.model

data class LightLevel(val lux: Float)

data class HardwareTemps(val cpuC: List<Float>, val skinC: List<Float>, val batteryC: List<Float>)
