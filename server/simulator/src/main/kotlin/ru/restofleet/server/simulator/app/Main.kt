package ru.restofleet.server.simulator.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import ru.restofleet.server.simulator.app.di.simulatorModule
import ru.restofleet.server.simulator.data.scenario.LoadedScenario
import ru.restofleet.server.simulator.data.scenario.ScenarioException
import ru.restofleet.server.simulator.data.scenario.YamlScenarioLoader
import ru.restofleet.server.simulator.domain.model.RunReport
import ru.restofleet.server.simulator.domain.port.CredentialStore
import ru.restofleet.server.simulator.domain.runtime.RunMetrics
import ru.restofleet.server.simulator.domain.stats.Percentiles
import ru.restofleet.server.simulator.domain.usecase.RegisterDevices
import ru.restofleet.server.simulator.domain.model.RegistrationRequest
import ru.restofleet.server.simulator.domain.usecase.RunExperiment
import java.io.IOException
import java.io.PrintStream
import java.util.Locale
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(SimulatorApp(System.out, System.err).run(args.toList()))
}

/** Точка входа: разбор командной строки и сценария, граф Koin, вызов use case, итог в stdout. */
class SimulatorApp(private val out: PrintStream, private val err: PrintStream) {
    fun run(args: List<String>): Int {
        val options = try {
            CliOptions.parse(args)
        } catch (e: CliException) {
            err.println(e.message)
            err.println(CliOptions.USAGE)
            null
        }
        val loaded = options?.let(::loadScenario) ?: return EXIT_USAGE
        val koin = koinApplication { modules(simulatorModule(options, loaded)) }.koin
        return try {
            runBlocking(Dispatchers.Default) { execute(koin, options, loaded) }
        } finally {
            koin.close()
        }
    }

    private fun loadScenario(options: CliOptions): LoadedScenario? = try {
        YamlScenarioLoader.load(options.scenario)
    } catch (e: ScenarioException) {
        err.println(e.message)
        null
    } catch (e: IOException) {
        err.println("cannot read scenario ${options.scenario}: ${e.javaClass.simpleName}")
        null
    }

    private suspend fun execute(koin: Koin, options: CliOptions, loaded: LoadedScenario): Int = when (options.command) {
        Command.REGISTER -> {
            val devices = options.devices ?: loaded.scenario.devices
            out.println("register: up to $devices devices")
            val report = koin.get<RegisterDevices>()(
                RegistrationRequest(devices, loaded.scenario.mix, options.parallelism),
            )
            out.println(
                "register: cached=${report.cached} registered=${report.registered} failed=${report.failed} " +
                    "in ${report.durationMs} ms ${report.failures}",
            )
            if (report.failed == 0) EXIT_OK else EXIT_FAILED
        }
        Command.RUN, Command.EXPERIMENT -> {
            val base = options.devices?.let { loaded.scenario.copy(devices = it) } ?: loaded.scenario
            val scenario = if (options.command == Command.RUN) base.copy(commands = null, loss = null) else base
            out.println(
                "${options.command.code}: ${scenario.name} mode=${scenario.mode.code} devices=${scenario.devices}",
            )
            val credentials = koin.get<CredentialStore>().load()
            val metrics = koin.get<RunMetrics>()
            val report = coroutineScope {
                val progress = launch { printProgress(metrics) }
                koin.get<RunExperiment>()(scenario, credentials).also { progress.cancel() }
            }
            out.println(summary(report))
            EXIT_OK
        }
    }

    private suspend fun printProgress(metrics: RunMetrics) {
        val started = System.nanoTime()
        while (true) {
            delay(PROGRESS_MS)
            val c = metrics.snapshot()
            out.println(
                "t=${(System.nanoTime() - started) / NANOS_IN_SEC}s connected=${c.connected} out=${c.messagesOut} " +
                    "in=${c.messagesIn} req=${c.requests} reqErr=${c.requestErrors} pubErr=${c.publishErrors} " +
                    "lost=${c.connectionLosses} connFail=${c.connectFailures}",
            )
        }
    }

    private fun summary(report: RunReport): String {
        val latency = Percentiles.summary(report.commands.mapNotNull { it.latencyMs })
        val detection = Percentiles.summary(report.detections.mapNotNull { it.offlineAfterMs?.toDouble() })
        return String.format(
            Locale.ROOT,
            "done: %s connected=%d commands=%d received=%d p50=%.1f ms p95=%.1f ms; lost=%d detected=%d p50=%.0f ms; " +
                "traffic rx=%s tx=%s",
            report.scenario.name, report.connectedAtWindowStart, report.commands.size, latency?.count ?: 0,
            latency?.p50 ?: Double.NaN, latency?.p95 ?: Double.NaN, report.detections.size, detection?.count ?: 0,
            detection?.p50 ?: Double.NaN, report.windowTraffic?.rxBytes ?: "n/a",
            report.windowTraffic?.txBytes ?: "n/a",
        )
    }

    companion object {
        const val EXIT_OK = 0
        const val EXIT_FAILED = 1
        const val EXIT_USAGE = 2
        private const val PROGRESS_MS = 10_000L
        private const val NANOS_IN_SEC = 1_000_000_000L
    }
}
