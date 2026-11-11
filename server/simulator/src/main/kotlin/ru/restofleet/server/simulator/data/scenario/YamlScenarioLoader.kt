package ru.restofleet.server.simulator.data.scenario

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import ru.restofleet.server.simulator.domain.model.CommandLoad
import ru.restofleet.server.simulator.domain.model.LossMode
import ru.restofleet.server.simulator.domain.model.LossTest
import ru.restofleet.server.simulator.domain.model.RoleMix
import ru.restofleet.server.simulator.domain.model.Scenario
import ru.restofleet.server.simulator.domain.model.TransportMode
import java.nio.file.Files
import java.nio.file.Path
import java.time.DateTimeException
import java.time.ZoneId

/**
 * Куда ходят устройства и измеритель. [api] — REST устройств (`/provision`, `/poll/…`), [control] — REST оператора
 * (команды, статусы; в compose — отдельная сеть, чтобы трафик измерителя не попадал в счётчик устройств, реш. 280),
 * [brokerHost]:[brokerPort] — MQTT (TLS по CA стенда).
 */
data class Endpoints(val api: String, val control: String, val brokerHost: String, val brokerPort: Int)

/** Сценарий и адреса из одного YAML-файла (FR-SM-07). */
data class LoadedScenario(val scenario: Scenario, val endpoints: Endpoints)

/** Ошибка сценария: текст для оператора, без значений секретов (их в сценарии нет). */
class ScenarioException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** YAML сценария (kaml, строгий режим: неизвестный ключ — ошибка). Примеры — каталог `tools/experiments/scenarios`. */
object YamlScenarioLoader {
    fun load(path: Path): LoadedScenario = parse(Files.readString(path))

    fun parse(text: String): LoadedScenario {
        val file = try {
            Yaml.default.decodeFromString(ScenarioFile.serializer(), text)
        } catch (e: SerializationException) {
            // YamlException kaml — тоже SerializationException
            invalid("invalid scenario YAML", e)
        }
        return try {
            file.toDomain()
        } catch (e: IllegalArgumentException) {
            invalid("invalid scenario", e)
        } catch (e: DateTimeException) {
            invalid("invalid scenario timezone", e)
        }
    }

    private fun invalid(what: String, cause: Exception): Nothing =
        throw ScenarioException("$what: ${cause.message}", cause)

    private fun ScenarioFile.toDomain(): LoadedScenario {
        ZoneId.of(timezone)
        val transport = TransportMode.entries.firstOrNull { it.code == mode }
            ?: throw ScenarioException("mode must be one of ${TransportMode.entries.joinToString { it.code }}")
        val scenario = Scenario(
            name = name,
            mode = transport,
            devices = devices,
            seed = seed,
            rampPerSec = rampPerSec,
            warmupSec = warmupSec,
            durationSec = durationSec,
            telemetryIntervalSec = telemetryIntervalSec,
            pollIntervalSec = pollIntervalSec,
            jitter = jitter,
            timeScale = timeScale,
            timezone = timezone,
            mix = RoleMix(mix.kitchen, mix.hall, mix.assembly),
            commands = commands?.let { CommandLoad(it.perMinute, it.type, it.ttlSec) },
            loss = loss?.let { file ->
                val lossMode = LossMode.entries.firstOrNull { it.code == file.mode }
                    ?: throw ScenarioException("loss.mode must be one of ${LossMode.entries.joinToString { it.code }}")
                LossTest(file.devices, lossMode, file.timeoutSec)
            },
            network = network,
        )
        return LoadedScenario(
            scenario,
            Endpoints(endpoints.api, endpoints.control ?: endpoints.api, endpoints.broker.host, endpoints.broker.port),
        )
    }
}

@Serializable
private data class ScenarioFile(
    val name: String,
    val mode: String,
    val devices: Int,
    val seed: Long = Scenario.DEFAULT_SEED,
    val rampPerSec: Int = Scenario.DEFAULT_RAMP_PER_SEC,
    val warmupSec: Int = Scenario.DEFAULT_WARMUP_SEC,
    val durationSec: Int = Scenario.DEFAULT_DURATION_SEC,
    val telemetryIntervalSec: Int = DEFAULT_INTERVAL,
    val pollIntervalSec: Int? = null,
    val jitter: Double = Scenario.DEFAULT_JITTER,
    val timeScale: Double = 1.0,
    val timezone: String = Scenario.DEFAULT_TIMEZONE,
    val mix: MixFile = MixFile(),
    val commands: CommandsFile? = null,
    val loss: LossFile? = null,
    val network: String = "baseline",
    val endpoints: EndpointsFile,
)

private const val DEFAULT_INTERVAL = 30
private const val DEFAULT_TTL = 300
private const val DEFAULT_MQTT_PORT = 8883
private const val DEFAULT_LOSS_TIMEOUT = 120

@Serializable
private data class MixFile(val kitchen: Int = 2, val hall: Int = 3, val assembly: Int = 1)

@Serializable
private data class CommandsFile(val perMinute: Int, val type: String = "ping", val ttlSec: Int = DEFAULT_TTL)

@Serializable
private data class LossFile(val devices: Int, val mode: String = "silent", val timeoutSec: Int = DEFAULT_LOSS_TIMEOUT)

@Serializable
private data class EndpointsFile(val api: String, val control: String? = null, val broker: BrokerFile = BrokerFile())

@Serializable
private data class BrokerFile(val host: String = "mosquitto", val port: Int = DEFAULT_MQTT_PORT)
