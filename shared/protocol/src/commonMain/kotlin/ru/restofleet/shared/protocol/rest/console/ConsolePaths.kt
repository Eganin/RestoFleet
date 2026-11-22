package ru.restofleet.shared.protocol.rest.console

import ru.restofleet.shared.protocol.rest.RestPaths

/**
 * Пути REST API консоли (ТЗ 8.2, `docs/openapi.yaml`). [ALL] — шаблоны с `{id}`: тест `server:api` сверяет их со
 * спецификацией, функции подставляют идентификатор (он должен быть уже проверен: `DeviceId` и UUID без `/`).
 */
object ConsolePaths {
    private const val P = RestPaths.API_PREFIX

    const val LOGIN = "$P/auth/login"
    const val REFRESH = "$P/auth/refresh"
    const val LOGOUT = "$P/auth/logout"
    const val SUMMARY = "$P/summary"
    const val RESTAURANTS = "$P/restaurants"
    const val DEVICES = "$P/devices"
    const val ALERTS = "$P/alerts"
    const val INCIDENTS = "$P/incidents"
    const val PROVISIONING_TOKENS = "$P/provisioning/tokens"
    const val WS = "$P/ws"

    val ALL: List<String> = listOf(
        LOGIN, REFRESH, LOGOUT, SUMMARY, RESTAURANTS, "$RESTAURANTS/{id}", DEVICES, "$DEVICES/{id}",
        "$DEVICES/{id}/telemetry", "$DEVICES/{id}/probes", "$DEVICES/{id}/events", "$DEVICES/{id}/shadow",
        "$DEVICES/{id}/commands", "$P/commands/{cmdId}", ALERTS, "$ALERTS/{id}", INCIDENTS, "$INCIDENTS/{id}",
        PROVISIONING_TOKENS, WS,
    )

    fun restaurant(id: String): String = "$RESTAURANTS/$id"
    fun device(id: String): String = "$DEVICES/$id"
    fun deviceTelemetry(id: String): String = "$DEVICES/$id/telemetry"
    fun deviceProbes(id: String): String = "$DEVICES/$id/probes"
    fun deviceEvents(id: String): String = "$DEVICES/$id/events"
    fun deviceShadow(id: String): String = "$DEVICES/$id/shadow"
    fun deviceCommands(id: String): String = "$DEVICES/$id/commands"
    fun command(cmdId: String): String = "$P/commands/$cmdId"
    fun alert(id: String): String = "$ALERTS/$id"
    fun incident(id: String): String = "$INCIDENTS/$id"
}
