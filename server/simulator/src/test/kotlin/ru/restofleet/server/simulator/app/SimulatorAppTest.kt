package ru.restofleet.server.simulator.app

import com.google.common.truth.Truth.assertThat
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koin.dsl.koinApplication
import ru.restofleet.server.simulator.app.di.simulatorModule
import ru.restofleet.server.simulator.data.scenario.YamlScenarioLoader
import ru.restofleet.server.simulator.data.store.JsonlCredentialStore
import ru.restofleet.server.simulator.domain.model.DeviceCredentials
import ru.restofleet.server.simulator.domain.model.DeviceRole
import ru.restofleet.server.simulator.domain.port.DeviceConnector
import ru.restofleet.server.simulator.domain.usecase.RegisterDevices
import ru.restofleet.server.simulator.domain.usecase.RunExperiment
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/** Командная строка, граф Koin и сквозной прогон REST-режима против поддельного бэкенда (MockWebServer). */
class SimulatorAppTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val server = MockWebServer()
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val app = SimulatorApp(PrintStream(out, true), PrintStream(err, true))

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun scenario(mode: String, vararg extra: String): Path =
        temp.newFile("$mode-${System.nanoTime()}.yaml").toPath().also {
        val lines = listOf(
            "name: app-$mode", "mode: $mode", "devices: 2", "rampPerSec: 10", "warmupSec: 0", "durationSec: 2",
            "pollIntervalSec: 1", "jitter: 0", *extra, "endpoints:",
            "  api: ${server.url("/").toString().removeSuffix("/")}", "  broker: { host: 127.0.0.1, port: 1 }",
        )
        Files.writeString(it, lines.joinToString("\n"))
    }

    private fun args(command: String, scenario: Path, vararg extra: String) = listOf(
        command, "--scenario", scenario.toString(), "--creds", temp.root.resolve("creds.jsonl").path,
        "--results", temp.root.resolve("results").path, "--ca", temp.root.resolve("missing-ca.crt").path,
        "--admin-password-file", temp.root.resolve("admin-password").path,
    ) + extra

    private fun seedCredentials(count: Int) {
        val store = JsonlCredentialStore(temp.root.toPath().resolve("creds.jsonl"))
        repeat(count) { store.append(DeviceCredentials("d-$it", "pw-$it", "r-1", DeviceRole.HALL)) }
    }

    @Test
    fun cliOptionsAreParsedWithContainerDefaults() {
        val options = CliOptions.parse(
            listOf("experiment", "--scenario", "/scenarios/a.yaml", "--devices", "1000", "--parallel", "4"),
        )
        assertThat(options.command).isEqualTo(Command.EXPERIMENT)
        assertThat(options.devices).isEqualTo(1000)
        assertThat(options.parallelism).isEqualTo(4)
        assertThat(options.credentials.toString()).isEqualTo("/var/lib/simulator/creds.jsonl")
        assertThat(options.adminPasswordFile.toString()).isEqualTo("/run/secrets/admin-password")
        listOf(
            listOf("fly", "--scenario", "a"),
            emptyList(),
            listOf("run", "--scenario"),
            listOf("run", "scenario", "a"),
            listOf("run", "--scenario", "a", "--colour", "red"),
            listOf("run", "--devices", "1"),
            listOf("run", "--scenario", "a", "--devices", "0"),
        ).forEach { bad -> assertFailsWith<CliException> { CliOptions.parse(bad) } }
    }

    @Test
    fun badArgumentsAndScenariosExitWithUsage() {
        assertThat(app.run(listOf("dance"))).isEqualTo(SimulatorApp.EXIT_USAGE)
        assertThat(err.toString()).contains("Использование")
        val absent = temp.root.resolve("absent.yaml").path
        assertThat(app.run(listOf("run", "--scenario", absent))).isEqualTo(SimulatorApp.EXIT_USAGE)
        val broken = temp.newFile("broken.yaml").toPath().also { Files.writeString(it, "name: [") }
        assertThat(app.run(listOf("run", "--scenario", broken.toString()))).isEqualTo(SimulatorApp.EXIT_USAGE)
    }

    @Test
    fun koinGraphResolvesForBothModes() {
        listOf("mqtt", "rest").forEach { mode ->
            val options = CliOptions.parse(args("experiment", scenario(mode)))
            val loaded = YamlScenarioLoader.load(options.scenario)
            val koin = koinApplication { modules(simulatorModule(options, loaded)) }.koin
            assertThat(koin.get<RunExperiment>()).isNotNull()
            assertThat(koin.get<RegisterDevices>()).isNotNull()
            val expected = if (mode == "mqtt") "Mqtt" else "Polling"
            assertThat(koin.get<DeviceConnector>().javaClass.simpleName).startsWith(expected)
            koin.close()
        }
    }

    @Test
    fun registerWithEnoughCachedDevicesDoesNotCallTheBackend() {
        seedCredentials(2)
        assertThat(app.run(args("register", scenario("rest")))).isEqualTo(SimulatorApp.EXIT_OK)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(out.toString()).contains("cached=2 registered=0 failed=0")
        assertThat(Files.readAllLines(temp.root.toPath().resolve("results/registration.csv"))).hasSize(2)
    }

    @Test
    fun registerReportsFailures() {
        Files.writeString(temp.root.toPath().resolve("admin-password"), "pw\n")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/v1/auth/login" -> json(200, """{"accessToken":"t"}""")
                "/api/v1/restaurants" -> json(200, """{"items":[{"id":"r-1"}]}""")
                "/api/v1/provisioning/tokens:batch" -> json(201, """{"items":[{"code":"A"},{"code":"B"}]}""")
                else -> json(409, """{"code":"token_used"}""")
            }
        }
        assertThat(app.run(args("register", scenario("rest"), "--devices", "1"))).isEqualTo(SimulatorApp.EXIT_FAILED)
        assertThat(out.toString()).contains("failed=1")
    }

    @Test
    fun restExperimentRunsEndToEndAgainstAFakeBackend() {
        seedCredentials(2)
        Files.writeString(temp.root.toPath().resolve("admin-password"), "pw\n")
        val polls = AtomicInteger()
        val pending = java.util.concurrent.ConcurrentLinkedQueue<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/v1/auth/login" -> json(200, """{"accessToken":"t"}""")
                "/api/v1/poll/commands" -> {
                    polls.incrementAndGet()
                    val cmd = pending.poll()
                    val list = cmd?.let { """{"cmdId":"$it","type":"ping","params":{}}""" }.orEmpty()
                    json(200, """{"commands":[$list]}""")
                }
                "/api/v1/poll/telemetry", "/api/v1/poll/commands/result" -> MockResponse.Builder().code(204).build()
                "/api/v1/commands/c-1" -> json(200, """{"status":"succeeded"}""")
                "/api/v1/devices/d-1" -> json(200, """{"device":{"status":"offline"}}""")
                else -> if (request.url.encodedPath.endsWith("/commands") && request.method == "POST") {
                    pending += "c-1"
                    json(201, """{"cmdId":"c-1"}""")
                } else {
                    json(404, """{"code":"not_found"}""")
                }
            }
        }
        val scenario = scenario("rest", "commands: { perMinute: 30 }", "loss: { devices: 1, timeoutSec: 5 }")

        assertThat(app.run(args("experiment", scenario))).isEqualTo(SimulatorApp.EXIT_OK)

        assertThat(polls.get()).isAtLeast(2)
        val dir = temp.root.toPath().resolve("results/app-rest")
        val summary = Files.readAllLines(dir.resolve("summary.csv"))
        val cells = summary[0].split(',').zip(summary[1].split(',')).toMap()
        assertThat(cells["commands_sent"]).isEqualTo("1")
        assertThat(cells["commands_received"]).isEqualTo("1")
        assertThat(cells["loss_detected"]).isEqualTo("1")
        assertThat(Files.readAllLines(dir.resolve("commands.csv"))[1]).endsWith(",succeeded")
        assertThat(out.toString()).contains("done: app-rest")
        assertThat(out.toString()).doesNotContain("pw-0")
    }

    private fun json(code: Int, body: String) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
}
