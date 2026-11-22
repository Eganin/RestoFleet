package ru.restofleet.server.api.health

import kotlinx.serialization.Serializable

/** `GET /health` (FR-BE-56): `status` — `ok` | `degraded`, по компоненту на зависимость (БД, брокер). */
@Serializable
data class HealthDto(val status: String, val components: Map<String, Boolean>)
