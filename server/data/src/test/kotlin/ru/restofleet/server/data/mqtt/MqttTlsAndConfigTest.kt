package ru.restofleet.server.data.mqtt

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.restofleet.server.data.config.DataConfig
import ru.restofleet.server.data.config.DatabaseConfig
import ru.restofleet.server.data.config.MqttConfig
import ru.restofleet.server.data.ingest.IngestMetrics
import java.io.File
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.test.assertFailsWith

class MqttTlsAndConfigTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val caPath: String = File(javaClass.getResource("/tls/test-ca.crt")!!.toURI()).absolutePath

    @Test
    fun `trust store holds only the stand CA, not the system ones`() {
        val manager = MqttTls.trustOnly(caPath).trustManagers.filterIsInstance<X509TrustManager>().single()

        val issuers: List<X509Certificate> = manager.acceptedIssuers.toList()
        assertThat(issuers).hasSize(1)
        assertThat(issuers.single().subjectX500Principal.name).contains("RestoFleet test CA")
    }

    @Test
    fun `file without certificates is rejected at startup`() {
        val empty = tmp.newFile("empty.crt")

        assertFailsWith<IllegalArgumentException> { MqttTls.trustOnly(empty.absolutePath) }
    }

    @Test
    fun `configs never print passwords`() {
        val db = DatabaseConfig("jdbc:postgresql://db/rf", "rf", "db-secret-1")
        val mqtt = MqttConfig("mosquitto", 8883, password = "mqtt-secret-2", caFile = "/ca.crt")

        val printed = DataConfig(db, mqtt).toString()

        assertThat(printed).doesNotContain("db-secret-1")
        assertThat(printed).doesNotContain("mqtt-secret-2")
        assertThat(printed).contains("password=***")
        assertThat(mqtt.clientId).isEqualTo("backend")
        assertThat(mqtt.username).isEqualTo("backend")
        assertThat(mqtt.sessionExpirySec).isEqualTo(86_400)
    }

    @Test
    fun `backend CONNECT limits packets from the broker to the agreed size`() {
        val connect = HiveMqClient.connectMessage(MqttConfig("mosquitto", 8883, password = "mqtt-secret-3"))

        // R-1: брокер не перешлёт бэкенду пакет больше этого размера (MQTT 5 Maximum Packet Size)
        assertThat(connect.restrictions.maximumPacketSize).isEqualTo(MqttConfig.DEFAULT_MAX_PACKET_SIZE)
        assertThat(connect.restrictions.receiveMaximum).isEqualTo(1_000)
        assertThat(connect.isCleanStart).isFalse()
        assertThat(connect.sessionExpiryInterval).isEqualTo(86_400)
    }

    @Test
    fun `metrics snapshot names every counter`() {
        val metrics = IngestMetrics().apply {
            received.addAndGet(5)
            poison.incrementAndGet()
        }

        assertThat(metrics.snapshot()).containsAtLeast("received", 5L, "poison", 1L, "undecodable", 0L)
        assertThat(metrics.snapshot().keys).containsExactly(
            "received", "batches", "applied", "ignored", "undecodable", "dropped", "retries", "poison",
        )
    }
}
