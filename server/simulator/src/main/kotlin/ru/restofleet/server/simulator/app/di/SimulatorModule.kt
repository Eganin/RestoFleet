package ru.restofleet.server.simulator.app.di

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.server.simulator.app.CliOptions
import ru.restofleet.server.simulator.data.csv.CsvResultSink
import ru.restofleet.server.simulator.data.mqtt.MqttDeviceConnector
import ru.restofleet.server.simulator.data.mqtt.MqttSettings
import ru.restofleet.server.simulator.data.net.InterfaceTrafficMeter
import ru.restofleet.server.simulator.data.net.StandTrust
import ru.restofleet.server.simulator.data.probe.HttpProbeRunner
import ru.restofleet.server.simulator.data.rest.OkHttpControlApi
import ru.restofleet.server.simulator.data.rest.OkHttpProvisioningApi
import ru.restofleet.server.simulator.data.rest.PollingDeviceConnector
import ru.restofleet.server.simulator.data.scenario.LoadedScenario
import ru.restofleet.server.simulator.data.store.JsonlCredentialStore
import ru.restofleet.server.simulator.domain.model.TransportMode
import ru.restofleet.server.simulator.domain.port.ControlApi
import ru.restofleet.server.simulator.domain.port.CredentialStore
import ru.restofleet.server.simulator.domain.port.DeviceConnector
import ru.restofleet.server.simulator.domain.port.ProbeRunner
import ru.restofleet.server.simulator.domain.port.ProvisioningApi
import ru.restofleet.server.simulator.domain.port.ResultSink
import ru.restofleet.server.simulator.domain.port.Ticker
import ru.restofleet.server.simulator.domain.port.TrafficMeter
import ru.restofleet.server.simulator.domain.runtime.CommandReceipts
import ru.restofleet.server.simulator.domain.runtime.DeviceRunner
import ru.restofleet.server.simulator.domain.runtime.RunMetrics
import ru.restofleet.server.simulator.domain.usecase.RegisterDevices
import ru.restofleet.server.simulator.domain.usecase.RunExperiment
import ru.restofleet.shared.kernel.Clock
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.kernel.SystemClock
import ru.restofleet.shared.kernel.UuidIdGenerator
import java.net.URI
import java.nio.file.Files
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Версия «агента» виртуальных устройств в `status` и `/provision`. */
const val SIMULATOR_VERSION = "simulator-1.0"

private val DEVICES = named("devices")
private val CONTROL = named("control")

/**
 * Composition root симулятора (Koin только здесь, реш. 279): адреса и режим — из сценария, пути — из командной
 * строки. Два HTTP-клиента с отдельными диспетчерами: устройства (до 1 024 одновременных запросов на хост) и оператор
 * — очередь опросов 5 000 устройств не задерживает команды измерителя.
 */
fun simulatorModule(options: CliOptions, loaded: LoadedScenario): Module = module {
    val endpoints = loaded.endpoints
    single<Clock> { SystemClock }
    single<Ticker> { Ticker { System.nanoTime() } }
    single<IdGenerator> { UuidIdGenerator() }
    single { RunMetrics() }
    single { CommandReceipts() }
    single { StandTls(options.caFile.takeIf { Files.isReadable(it) }?.let { StandTrust.fromCaFile(it.toFile()) }) }
    single(DEVICES) { httpClient(get<StandTls>().trust, DEVICE_MAX_REQUESTS) }
    single(CONTROL) { httpClient(get<StandTls>().trust, CONTROL_MAX_REQUESTS) }
    single<ControlApi> {
        OkHttpControlApi(get(CONTROL), endpoints.control, options.adminLogin) {
            Files.readString(options.adminPasswordFile).trim()
        }
    }
    single<ProvisioningApi> { OkHttpProvisioningApi(get(CONTROL), endpoints.api, SIMULATOR_VERSION) }
    single<CredentialStore> { JsonlCredentialStore(options.credentials) }
    single<ResultSink> { CsvResultSink(options.results, get()) }
    single<TrafficMeter> {
        val deviceHost = when (loaded.scenario.mode) {
            TransportMode.MQTT -> endpoints.brokerHost
            TransportMode.REST -> URI(endpoints.api).host
        }
        InterfaceTrafficMeter(InterfaceTrafficMeter.interfaceFor(deviceHost))
    }
    single<ProbeRunner> { HttpProbeRunner(get(DEVICES), get()) }
    single<DeviceConnector> {
        when (loaded.scenario.mode) {
            TransportMode.MQTT -> MqttDeviceConnector(
                MqttSettings(endpoints.brokerHost, endpoints.brokerPort),
                get<StandTls>().trust, get(), get(), get(), get(),
            )
            TransportMode.REST -> PollingDeviceConnector(get(DEVICES), endpoints.api, get(), get())
        }
    }
    single { DeviceRunner(get(), get(), get(), get()) }
    single { RunExperiment(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { RegisterDevices(get(), get(), get(), get(), get()) }
}

private const val DEVICE_MAX_REQUESTS = 1_024
private const val CONTROL_MAX_REQUESTS = 64
private const val CONNECT_TIMEOUT_SEC = 10L
private const val READ_TIMEOUT_SEC = 30L
private const val CALL_TIMEOUT_SEC = 60L

private fun httpClient(trust: TrustManagerFactory?, maxRequests: Int): OkHttpClient =
    OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            this.maxRequests = maxRequests
            maxRequestsPerHost = maxRequests
        })
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
        .readTimeout(Duration.ofSeconds(READ_TIMEOUT_SEC))
        .callTimeout(Duration.ofSeconds(CALL_TIMEOUT_SEC))
        .apply {
            trust?.let {
                val context = SSLContext.getInstance("TLS").apply { init(null, it.trustManagers, null) }
                sslSocketFactory(context.socketFactory, StandTrust.x509(it))
            }
        }
        .build()
