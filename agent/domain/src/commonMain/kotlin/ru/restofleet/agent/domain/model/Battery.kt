package ru.restofleet.agent.domain.model

private val LEVEL_RANGE = 0..100

enum class PowerSource { AC, USB, WIRELESS, DOCK, NONE }

data class BatteryState(
    val levelPct: Int,
    val charging: Boolean,
    val source: PowerSource,
    val tempC: Double?,
) {
    init {
        require(levelPct in LEVEL_RANGE) { "levelPct must be in 0..100, was $levelPct" }
    }
}
