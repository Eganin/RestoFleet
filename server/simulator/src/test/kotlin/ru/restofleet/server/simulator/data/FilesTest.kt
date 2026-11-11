package ru.restofleet.server.simulator.data

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.restofleet.server.simulator.data.csv.CsvResultSink
import ru.restofleet.server.simulator.data.net.InterfaceTrafficMeter
import ru.restofleet.server.simulator.data.scenario.ScenarioException
import ru.restofleet.server.simulator.data.scenario.YamlScenarioLoader
import ru.restofleet.server.simulator.data.store.JsonlCredentialStore
import ru.restofleet.server.simulator.domain.model.CommandLoad
import ru.restofleet.server.simulator.domain.model.CommandRecord
import ru.restofleet.server.simulator.domain.model.CounterSnapshot
import ru.restofleet.server.simulator.domain.model.DetectionRecord
import ru.restofleet.server.simulator.domain.model.DeviceCredentials
import ru.restofleet.server.simulator.domain.model.DeviceRole
import ru.restofleet.server.simulator.domain.model.LossMode
import ru.restofleet.server.simulator.domain.model.RegistrationReport
import ru.restofleet.server.simulator.domain.model.RunReport
import ru.restofleet.server.simulator.domain.model.Scenario
import ru.restofleet.server.simulator.domain.model.TimelineRow
import ru.restofleet.server.simulator.domain.model.TrafficSnapshot
import ru.restofleet.server.simulator.domain.model.TransportMode
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertFailsWith

/** Кэш учёток (600), сценарий YAML, CSV-результаты, счётчик байт интерфейса. */
class FilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun credentialsAreAppendedWithOwnerOnlyPermissionsAndSurviveBrokenLines() {
        val file = temp.root.toPath().resolve("sim/creds.jsonl")
        val store = JsonlCredentialStore(file)
        assertThat(store.load()).isEmpty()
        val a = DeviceCredentials("d-1", "secret-1", "r-1", DeviceRole.KITCHEN)
        val b = DeviceCredentials("d-2", "secret-2", "r-2", DeviceRole.ASSEMBLY)
        store.append(a)
        Files.writeString(file, "{\"deviceId\":\"broken\n", java.nio.file.StandardOpenOption.APPEND)
        store.append(b)
        assertThat(JsonlCredentialStore(file).load()).containsExactly(a, b).inOrder()
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------")
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parent))).isEqualTo("rwx------")
        Files.writeString(file, "{\"deviceId\":\"d-3\",\"password\":\"p\",\"restaurantId\":\"r\",\"role\":\"chef\"}\n")
        store.append(b)
        assertThat(store.load()).containsExactly(b)
    }

    @Test
    fun scenarioYamlIsParsedWithDefaults() {
        val loaded = YamlScenarioLoader.parse(
            """
            name: rest5-1000
            mode: rest
            devices: 1000
            pollIntervalSec: 5
            commands: { perMinute: 60, ttlSec: 120 }
            loss: { devices: 10, mode: close, timeoutSec: 90 }
            network: latency300
            endpoints:
              api: http://toxiproxy:18080
              control: http://backend-ctl:8080
              broker: { host: toxiproxy, port: 18883 }
            """.trimIndent(),
        )
        val scenario = loaded.scenario
        assertThat(scenario.mode).isEqualTo(TransportMode.REST)
        assertThat(scenario.pollIntervalSec).isEqualTo(5)
        assertThat(scenario.telemetryIntervalSec).isEqualTo(30)
        assertThat(scenario.commands).isEqualTo(CommandLoad(60, "ping", 120))
        assertThat(scenario.loss!!.mode).isEqualTo(LossMode.CLOSE)
        assertThat(scenario.network).isEqualTo("latency300")
        assertThat(loaded.endpoints.control).isEqualTo("http://backend-ctl:8080")
        assertThat(loaded.endpoints.brokerHost).isEqualTo("toxiproxy")
        assertThat(loaded.endpoints.brokerPort).isEqualTo(18883)

        val minimal = YamlScenarioLoader.parse(
            "name: m\nmode: mqtt\ndevices: 1\nendpoints: { api: http://backend:8080 }",
        )
        assertThat(minimal.endpoints.control).isEqualTo("http://backend:8080")
        assertThat(minimal.endpoints.brokerHost).isEqualTo("mosquitto")
        assertThat(minimal.scenario.loss).isNull()
        val file = temp.newFile("s.yaml").toPath()
        Files.writeString(file, "name: f\nmode: mqtt\ndevices: 2\nendpoints: { api: http://b:1 }")
        assertThat(YamlScenarioLoader.load(file).scenario.devices).isEqualTo(2)
    }

    @Test
    fun invalidScenariosAreReportedAsScenarioErrors() {
        val base = "name: x\ndevices: 1\nendpoints: { api: http://b:1 }\n"
        listOf(
            base + "mode: carrier-pigeon",
            base + "mode: rest",
            base + "mode: mqtt\ntimezone: Mars/Olympus",
            base + "mode: mqtt\nunknownKey: 1",
            base + "mode: mqtt\nloss: { devices: 1, mode: vanish }",
            "name: [",
        ).forEach { yaml -> assertFailsWith<ScenarioException> { YamlScenarioLoader.parse(yaml) } }
    }

    @Test
    fun resultsAreWrittenAsCsvWithoutSecrets() {
        val sink = CsvResultSink(temp.root.toPath(), clock = { 1_000 })
        sink.registration(RegistrationReport(10, 4, 5, 1, 2_000, mapOf("token_used" to 1)))
        sink.registration(RegistrationReport(10, 9, 1, 0, 100, emptyMap()))
        val registration = Files.readAllLines(temp.root.toPath().resolve("registration.csv"))
        assertThat(registration).containsExactly(
            "at_ms,requested,cached,registered,failed,duration_ms,failures",
            "1000,10,4,5,1,2000,token_used=1",
            "1000,10,9,1,0,100,",
        ).inOrder()

        val counters = CounterSnapshot(3, 0, 1, 10, 4, 0, 0, 0, 900, 300)
        val report = RunReport(
            scenario = Scenario("mqtt-3", TransportMode.MQTT, 3),
            startedAtMillis = 0, rampEndMillis = 100, windowStartMillis = 1_000, windowEndMillis = 61_000,
            connectedAtWindowStart = 3, windowCounters = counters, windowTraffic = TrafficSnapshot(5_000, 7_000),
            commands = listOf(
                CommandRecord("c-1", "d-1", 1_500, 12.3456, "succeeded"),
                CommandRecord("c-2", "d-2", 2_500, null, null),
            ),
            commandSendFailures = 0,
            detections = listOf(DetectionRecord("d-3", 62_000, 45_500, "offline")),
            timeline = listOf(TimelineRow(1_000, "window", counters, null)),
        )
        sink.run(report)
        val dir = temp.root.toPath().resolve("mqtt-3")
        val summary = Files.readAllLines(dir.resolve("summary.csv"))
        val header = summary[0].split(',')
        val row = summary[1].split(',')
        assertThat(row).hasSize(header.size)
        val cells = header.zip(row).toMap()
        assertThat(cells["window_sec"]).isEqualTo("60")
        assertThat(cells["rx_bytes"]).isEqualTo("5000")
        assertThat(cells["commands_received"]).isEqualTo("1")
        assertThat(cells["latency_p50_ms"]).isEqualTo("12.346")
        assertThat(cells["detection_p50_ms"]).isEqualTo("45500.000")
        assertThat(Files.readAllLines(dir.resolve("commands.csv"))).containsExactly(
            "cmd_id,device_id,sent_at_ms,latency_ms,final_status", "c-1,d-1,1500,12.346,succeeded", "c-2,d-2,2500,,",
        ).inOrder()
        assertThat(Files.readAllLines(dir.resolve("detection.csv"))[1]).isEqualTo("d-3,62000,45500,offline")
        assertThat(Files.readAllLines(dir.resolve("timeline.csv"))[1]).isEqualTo("1000,window,3,10,4,0,0,0,1,900,300,,")
    }

    @Test
    fun trafficIsReadFromInterfaceCounters() {
        val sys = temp.newFolder("net").toPath()
        val stats = Files.createDirectories(sys.resolve("eth0/statistics"))
        Files.writeString(stats.resolve("rx_bytes"), "12345\n")
        Files.writeString(stats.resolve("tx_bytes"), "678\n")
        assertThat(InterfaceTrafficMeter("eth0", sys).snapshot()).isEqualTo(TrafficSnapshot(12_345, 678))
        assertThat(InterfaceTrafficMeter("eth9", sys).snapshot()).isNull()
        assertThat(InterfaceTrafficMeter(null, sys).snapshot()).isNull()
        Files.writeString(stats.resolve("tx_bytes"), "garbage")
        assertThat(InterfaceTrafficMeter("eth0", sys).snapshot()).isNull()
    }

    @Test
    fun interfaceIsChosenBySubnet() {
        fun ip(text: String) = InetAddress.getByName(text) as Inet4Address
        assertThat(InterfaceTrafficMeter.sameSubnet(ip("172.18.0.5"), ip("172.18.3.9"), 16)).isTrue()
        assertThat(InterfaceTrafficMeter.sameSubnet(ip("172.18.0.5"), ip("172.19.0.5"), 16)).isFalse()
        assertThat(InterfaceTrafficMeter.sameSubnet(ip("10.0.0.1"), ip("10.0.0.1"), 0)).isFalse()
        assertThat(InterfaceTrafficMeter.interfaceFor("localhost")).isNull()
        assertThat(InterfaceTrafficMeter.interfaceFor("no-such-host.invalid")).isNull()
    }
}
