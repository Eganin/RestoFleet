package ru.restofleet.server.data.health

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.restofleet.server.data.mqtt.HiveMqClient
import ru.restofleet.server.domain.health.HealthProbe
import javax.sql.DataSource

/** `GET /health`: соединение из пула отвечает за [timeoutSec]. */
class DatabaseHealthProbe(private val dataSource: DataSource, private val timeoutSec: Int = 2) : HealthProbe {
    override val name: String = "database"

    override suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        try {
            dataSource.connection.use { it.isValid(timeoutSec) }
        } catch (_: java.sql.SQLException) {
            false
        }
    }
}

/** `GET /health`: MQTT-соединение бэкенда установлено. */
class BrokerHealthProbe(private val client: HiveMqClient) : HealthProbe {
    override val name: String = "broker"

    override suspend fun isHealthy(): Boolean = client.isConnected
}
