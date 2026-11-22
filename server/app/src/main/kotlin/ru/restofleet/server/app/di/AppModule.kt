package ru.restofleet.server.app.di

import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import ru.restofleet.server.api.ApiUseCases
import ru.restofleet.server.api.AuthUseCases
import ru.restofleet.server.api.CommandUseCases
import ru.restofleet.server.api.DevicePayloadDecoder
import ru.restofleet.server.api.DeviceUseCases
import ru.restofleet.server.api.PollUseCases
import ru.restofleet.server.api.ProvisioningUseCases
import ru.restofleet.server.api.RestaurantUseCases
import ru.restofleet.server.api.ShadowUseCases
import ru.restofleet.server.api.di.apiModule
import ru.restofleet.server.app.config.JobsSection
import ru.restofleet.server.app.config.RuntimeConfig
import ru.restofleet.server.app.serve.IngestLifecycle
import ru.restofleet.server.app.serve.PeriodicJob
import ru.restofleet.server.data.di.dataModule
import ru.restofleet.server.data.di.domainModule
import ru.restofleet.server.data.ingest.IngestMetrics
import ru.restofleet.server.data.ingest.RestPayloadDecoder
import ru.restofleet.server.data.mqtt.MqttBackend
import ru.restofleet.server.domain.command.CommandReport
import ru.restofleet.server.domain.command.usecase.ExpireCommands
import ru.restofleet.server.domain.ingest.DeviceDataBatch
import ru.restofleet.server.domain.polling.usecase.MarkStalePollers
import ru.restofleet.shared.kernel.DeviceId
import ru.restofleet.shared.kernel.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Composition root бэкенда (план 2.4): настройки из конфигурации (с прочитанными секретами), мост порта разбора тел
 * REST-опроса на декодер `server:data` (реш. 246), адаптер ingest и периодические задачи. `domainModule` остаётся в
 * `server:data` (реш. 251).
 */
fun appModule(runtime: RuntimeConfig): Module = module {
    single { runtime.data }
    single { runtime.provisioning }
    single { runtime.polling }
    single { runtime.auth }
    runtime.api?.let { api -> single { api } }
    single<DevicePayloadDecoder> { DataPayloadDecoder(get()) }
    single<IngestLifecycle> { MqttIngest(get()) }
    single { runtime.jobs }
    singleOf(::ServeJobs)
}

/**
 * Периодические задачи `serve` (контракт КМ2b): `ExpireCommands` и `MarkStalePollers` каждые 5 с, счётчики ingest в
 * журнал раз в минуту (только при изменении). Use cases без состояния — один экземпляр на всё время работы.
 */
class ServeJobs(
    expireCommands: ExpireCommands,
    markStalePollers: MarkStalePollers,
    metrics: IngestMetrics,
    settings: JobsSection,
) {
    private var lastMetrics = emptyMap<String, Long>()

    val all: List<PeriodicJob> = listOf(
        PeriodicJob("expire_commands", settings.expireCommandsMs) { expireCommands() },
        PeriodicJob("mark_stale_pollers", settings.markStalePollersMs) { markStalePollers() },
        PeriodicJob("ingest_metrics", settings.metricsLogMs) {
            val snapshot = metrics.snapshot()
            if (snapshot != lastMetrics) {
                lastMetrics = snapshot
                IngestMetricsLog.log(snapshot)
            }
            0
        },
    )
}

/** Модули `serve`; для `migrate`/`seed` — без [apiModule] (JWT им не нужен). */
fun serveModules(runtime: RuntimeConfig): List<Module> = listOf(appModule(runtime), dataModule, domainModule, apiModule)

fun databaseModules(runtime: RuntimeConfig): List<Module> = listOf(appModule(runtime), dataModule, domainModule)

/** Use cases маршрутам — параметрами (арх. разд. 8): `get()` только здесь, в composition root. */
fun apiUseCases(koin: Koin): ApiUseCases = with(koin) {
    ApiUseCases(
        auth = AuthUseCases(get(), get()),
        restaurants = RestaurantUseCases(get(), get(), get(), get()),
        devices = DeviceUseCases(get(), get(), get(), get(), get(), get()),
        shadows = ShadowUseCases(get(), get()),
        commands = CommandUseCases(get(), get()),
        provisioning = ProvisioningUseCases(get(), get()),
        poll = PollUseCases(get(), get(), get(), get(), get()),
        health = get(),
    )
}

/** Порт api ← декодер data: те же правила, что MQTT-ingest (реш. 241, 246). */
internal class DataPayloadDecoder(private val decoder: RestPayloadDecoder) : DevicePayloadDecoder {
    override fun telemetryBatch(deviceId: DeviceId, body: String): Outcome<DeviceDataBatch, String> =
        decoder.telemetryBatch(deviceId, body)

    override fun commandReport(deviceId: DeviceId, body: String): Outcome<CommandReport, String> =
        decoder.commandReport(deviceId, body)
}

internal class MqttIngest(private val backend: MqttBackend) : IngestLifecycle {
    override fun start(scope: CoroutineScope): Job = backend.start(scope)

    override suspend fun stop() = backend.stop()
}

internal object IngestMetricsLog {
    private val log = KotlinLogging.logger("ru.restofleet.server.app.Ingest")

    fun log(snapshot: Map<String, Long>) {
        log.info { "ingest ${snapshot.entries.joinToString(" ") { "${it.key}=${it.value}" }}" }
    }
}
