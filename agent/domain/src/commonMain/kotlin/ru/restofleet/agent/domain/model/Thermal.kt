package ru.restofleet.agent.domain.model

enum class ThermalStatus { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN }

data class ThermalState(val status: ThermalStatus?, val headroom: Double?)
