package ru.restofleet.server.app.serve

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import ru.restofleet.server.api.ApiSettings
import ru.restofleet.server.api.restofleetApi
import ru.restofleet.server.app.config.AppConfig
import ru.restofleet.server.app.config.RuntimeConfig
import ru.restofleet.server.app.di.ServeJobs
import ru.restofleet.server.app.di.apiUseCases
import ru.restofleet.server.app.di.serveModules
import javax.sql.DataSource
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private val log = KotlinLogging.logger("ru.restofleet.server.app.Serve")

/**
 * `serve` (план 2.4): Netty + `install(Koin)`, REST API, ingest MQTT и фоновые задачи. Остановка (SIGTERM, `docker
 * stop`): Ktor поднимает `ApplicationStopPreparing` — здесь останавливаются ingest и задачи, затем плагин Koin на
 * `ApplicationStopping` закрывает граф (пул Hikari), то есть порядок «ingest `stop()` → `koin.close()`».
 */
fun serve(config: AppConfig) {
    val runtime = RuntimeConfig.forServe(config)
    log.info { "starting: http ${config.http.host}:${config.http.port}, ${runtime.data}" }
    embeddedServer(Netty, port = config.http.port, host = config.http.host) {
        backendModule(runtime)
    }.start(wait = true)
}

internal fun Application.backendModule(runtime: RuntimeConfig) {
    install(Koin) {
        slf4jLogger()
        modules(serveModules(runtime))
    }
    val koin = getKoin()
    // Пул БД — сразу: недоступная БД на старте роняет процесс (compose перезапустит), а не первый запрос
    koin.get<DataSource>()
    restofleetApi(apiUseCases(koin), koin.get<ApiSettings>())

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            log.error { "background failure: ${e.javaClass.name}" }
        },
    )
    val background = BackendRuntime(
        scope = scope,
        ingest = koin.get(),
        jobs = koin.get<ServeJobs>().all,
        onFatal = { e ->
            log.error { "ingest stopped: ${e.javaClass.name} — exiting (broker refused subscription?)" }
            // Не из потока корутины: exitProcess запускает shutdown hook Ktor, который ждёт остановки фоновой части
            thread(name = "fatal-exit") { exitProcess(EXIT_INGEST_FAILED) }
        },
    )
    monitor.subscribe(ApplicationStarted) {
        background.start()
        log.info { "backend started: ingest and background jobs are running" }
    }
    monitor.subscribe(ApplicationStopPreparing) {
        runBlocking { background.stop() }
        scope.cancel()
    }
}

private const val EXIT_INGEST_FAILED = 3
