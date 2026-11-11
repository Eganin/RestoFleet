package ru.restofleet.server.data.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/** JSON-документ хранится текстом: разбор — в мапперах data-слоя, БД проверяет корректность (`jsonb`). */
private fun Table.json(name: String) = jsonb<String>(name, { it }, { it })

internal object RestaurantsTable : Table("restaurants") {
    val id = javaUUID("id")
    val code = varchar("code", 32)
    val name = varchar("name", 200)
    val city = varchar("city", 200)
    val address = varchar("address", 200)
    val timezone = varchar("timezone", 64)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

internal object DevicesTable : Table("devices") {
    val id = varchar("id", 64)
    val restaurantId = javaUUID("restaurant_id")
    val role = varchar("role", 16)
    val name = varchar("name", 100).nullable()
    val status = varchar("status", 16)
    val transport = varchar("transport", 8).nullable()
    val lastSeenAt = timestampWithTimeZone("last_seen_at").nullable()
    val decommissionedAt = timestampWithTimeZone("decommissioned_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val model = varchar("model", 100).nullable()
    val manufacturer = varchar("manufacturer", 100).nullable()
    val osVersion = varchar("os_version", 32).nullable()
    val apiLevel = integer("api_level").nullable()
    val agentVersion = varchar("agent_version", 64).nullable()
    val mode = varchar("mode", 32).nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object DeviceCredentialsTable : Table("device_credentials") {
    val deviceId = varchar("device_id", 64)
    val passwordHash = varchar("password_hash", 100)
    val createdAt = timestampWithTimeZone("created_at")
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    override val primaryKey = PrimaryKey(deviceId)
}

internal object ProvisioningTokensTable : Table("provisioning_tokens") {
    val id = javaUUID("id")
    val tokenHash = char("token_hash", 64)
    val restaurantId = javaUUID("restaurant_id")
    val role = varchar("role", 16)
    val name = varchar("name", 100).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val usedAt = timestampWithTimeZone("used_at").nullable()
    val deviceId = varchar("device_id", 64).nullable()
    override val primaryKey = PrimaryKey(id)
}

internal object DeviceSessionsTable : Table("device_sessions") {
    val deviceId = varchar("device_id", 64)
    val connId = varchar("conn_id", 64)
    val connectedAt = timestampWithTimeZone("connected_at")
    val disconnectedAt = timestampWithTimeZone("disconnected_at").nullable()
    val reason = varchar("reason", 32).nullable()
    override val primaryKey = PrimaryKey(deviceId, connId)
}

internal object ShadowsTable : Table("shadows") {
    val deviceId = varchar("device_id", 64)
    val desired = json("desired")
    val desiredVersion = long("desired_version")
    val desiredUpdatedAt = timestampWithTimeZone("desired_updated_at")
    val reported = json("reported").nullable()
    val reportedVersion = long("reported_version").nullable()
    val appliedAt = timestampWithTimeZone("applied_at").nullable()
    val reportedAt = timestampWithTimeZone("reported_at").nullable()
    val errors = json("errors")
    override val primaryKey = PrimaryKey(deviceId)
}

internal object TelemetryTable : Table("telemetry") {
    val deviceId = varchar("device_id", 64)
    val ts = timestampWithTimeZone("ts")
    val seq = long("seq")
    val batteryLevel = short("battery_level").nullable()
    val charging = bool("charging").nullable()
    val tempC = double("temp_c").nullable()
    val thermal = varchar("thermal", 16).nullable()
    val rssi = integer("rssi").nullable()
    val freeMb = long("free_mb").nullable()
    val availMb = long("avail_mb").nullable()
    val fgApp = varchar("fg_app", 255).nullable()
    val payload = json("payload")
    override val primaryKey = PrimaryKey(deviceId, ts)
}

internal object ProbeResultsTable : Table("probe_results") {
    val deviceId = varchar("device_id", 64)
    val ts = timestampWithTimeZone("ts")
    val target = varchar("target", 64)
    val ok = bool("ok")
    val status = integer("status").nullable()
    val error = varchar("error", 32).nullable()
    val totalMs = long("total_ms")
    val dnsMs = long("dns_ms").nullable()
    val connectMs = long("connect_ms").nullable()
    val tlsMs = long("tls_ms").nullable()
    val ttfbMs = long("ttfb_ms").nullable()
    override val primaryKey = PrimaryKey(deviceId, ts, target)
}

internal object EventsTable : Table("events") {
    val id = long("id").autoIncrement()
    val deviceId = varchar("device_id", 64)
    val ts = timestampWithTimeZone("ts")
    val type = varchar("type", 32)
    val payload = json("payload")
    override val primaryKey = PrimaryKey(id)
}

internal object CommandsTable : Table("commands") {
    val cmdId = varchar("cmd_id", 64)
    val deviceId = varchar("device_id", 64)
    val type = varchar("type", 64)
    val params = json("params")
    val status = varchar("status", 16)
    val createdAt = timestampWithTimeZone("created_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val deliveredAt = timestampWithTimeZone("delivered_at").nullable()
    val finishedAt = timestampWithTimeZone("finished_at").nullable()
    val result = json("result").nullable()
    val reason = text("reason").nullable()
    override val primaryKey = PrimaryKey(cmdId)
}

internal object UsersTable : Table("users") {
    val id = javaUUID("id")
    val login = varchar("login", 64)
    val passwordHash = varchar("password_hash", 100)
    val role = varchar("role", 16)
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

internal object RefreshTokensTable : Table("refresh_tokens") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id")
    val tokenHash = char("token_hash", 64)
    val createdAt = timestampWithTimeZone("created_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
