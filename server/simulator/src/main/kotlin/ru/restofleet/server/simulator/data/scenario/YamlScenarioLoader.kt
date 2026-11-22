package ru.restofleet.server.simulator.data.scenario

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import ru.restofleet.server.simulator.domain.fault.DeviceFault
import ru.restofleet.server.simulator.domain.fault.DeviceFaultType
import ru.restofleet.server.simulator.domain.model.CommandLoad
import ru.restofleet.server.simulator.domain.model.EpisodePlan
import ru.restofleet.server.simulator.domain.model.FleetSpec
import ru.restofleet.server.simulator.domain.model.LossMode
import ru.restofleet.server.simulator.domain.model.LossTest
import ru.restofleet.server.simulator.domain.model.ProbeTarget
import ru.restofleet.server.simulator.domain.model.RoleMix
import ru.restofleet.server.simulator.domain.model.Scenario
import ru.restofleet.server.simulator.domain.model.TransportMode
import ru.restofleet.server.simulator.domain.synthetic.EpisodeGenerator
import ru.restofleet.server.simulator.domain.synthetic.NetworkVariant
import ru.restofleet.server.simulator.domain.synthetic.Outage
import ru.restofleet.server.simulator.domain.synthetic.OutageKind
import ru.restofleet.server.simulator.domain.synthetic.ProbeMode
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
            probeMode = probeMode(probeMode),
            syntheticTargets = syntheticTargets?.map { it.toDomain() } ?: Scenario.DEFAULT_SYNTHETIC_TARGETS,
            faults = faults.map { it.toDomain() },
            outages = outages.map { it.toDomain() },
            episodes = episodes?.let {
                EpisodePlan(it.rounds, it.roundSec, it.activeFromSec, it.activeSec, it.globalShare)
            },
            fleet = fleet?.let { FleetSpec(it.prefix, it.restaurants, it.perRestaurant) },
        )
        return LoadedScenario(
            scenario,
            Endpoints(endpoints.api, endpoints.control ?: endpoints.api, endpoints.broker.host, endpoints.broker.port),
        )
    }
}

private fun probeMode(code: String): ProbeMode = ProbeMode.fromCode(code)
    ?: throw ScenarioException("probeMode must be one of ${ProbeMode.entries.map { it.code }}")

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
    val probeMode: String = "real",
    val syntheticTargets: List<TargetFile>? = null,
    val faults: List<FaultFile> = emptyList(),
    val outages: List<OutageFile> = emptyList(),
    val episodes: EpisodesFile? = null,
    val fleet: FleetFile? = null,
    val endpoints: EndpointsFile,
)

/** `faults:` — отказ устройств (FR-SM-04). */
@Serializable
private data class FaultFile(
    val type: String,
    val atSec: Int,
    val durationSec: Int,
    val devices: Int = 1,
    val offset: Int = 0,
) {
    fun toDomain() = DeviceFault(
        DeviceFaultType.fromCode(type)
            ?: throw ScenarioException("faults.type must be one of ${DeviceFaultType.entries.map { it.code }}"),
        atSec, durationSec, devices, offset,
    )
}

/** `outages:` — отказ сети или бэкенда (FR-SM-05); рестораны — коды. */
@Serializable
private data class OutageFile(
    val kind: String,
    val atSec: Int,
    val durationSec: Int,
    val restaurants: List<String> = emptyList(),
    val devices: List<String> = emptyList(),
    val variant: String = "offline",
    val latencyMs: Long = Outage.DEFAULT_DEGRADED_MS,
) {
    fun toDomain() = Outage(
        kind = OutageKind.fromCode(kind)
            ?: throw ScenarioException("outages.kind must be one of ${OutageKind.entries.joinToString { it.code }}"),
        fromMs = atSec * MS,
        toMs = (atSec + durationSec) * MS,
        restaurants = restaurants.toSet(),
        devices = devices.toSet(),
        variant = NetworkVariant.fromCode(variant)
            ?: throw ScenarioException("outages.variant must be one of ${NetworkVariant.entries.map { it.code }}"),
        latencyMs = latencyMs,
    )
}

@Serializable
private data class EpisodesFile(
    val rounds: Int,
    val roundSec: Int = EpisodeGenerator.DEFAULT_ROUND_SEC,
    val activeFromSec: Int = EpisodeGenerator.DEFAULT_ACTIVE_FROM_SEC,
    val activeSec: Int = EpisodeGenerator.DEFAULT_ACTIVE_SEC,
    val globalShare: Double = EpisodeGenerator.DEFAULT_GLOBAL_SHARE,
)

@Serializable
private data class FleetFile(val prefix: String, val restaurants: Int, val perRestaurant: Int = 6)

/** Цель проверки режима `synthetic`. */
@Serializable
private data class TargetFile(
    val id: String,
    val intervalSec: Int = 15,
    val failThreshold: Int = 3,
    val reference: Boolean = false,
) {
    fun toDomain() = ProbeTarget(id, "http", "synthetic://$id", intervalSec, TIMEOUT_MS, failThreshold, reference)
}

private const val MS = 1_000L
private const val TIMEOUT_MS = 5_000L

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
