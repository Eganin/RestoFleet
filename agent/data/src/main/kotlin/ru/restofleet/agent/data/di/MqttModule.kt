package ru.restofleet.agent.data.di

import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import ru.restofleet.agent.data.collectors.NetworkChangeObserver
import ru.restofleet.agent.data.collectors.PackageEventsObserver
import ru.restofleet.agent.data.deviceadmin.DeviceOwnerRole
import ru.restofleet.agent.data.mqtt.AndroidBootIdSource
import ru.restofleet.agent.data.mqtt.ConflatedFlushTrigger
import ru.restofleet.agent.data.mqtt.DeviceFacts
import ru.restofleet.agent.data.mqtt.EventPump
import ru.restofleet.agent.data.mqtt.HiveMqLink
import ru.restofleet.agent.data.mqtt.JsonPayloadEncoder
import ru.restofleet.agent.data.mqtt.MqttBrokerSession
import ru.restofleet.agent.data.mqtt.MqttLinkFactory
import ru.restofleet.agent.data.mqtt.MqttSessionCheck
import ru.restofleet.agent.data.mqtt.SessionConnectionMonitor
import ru.restofleet.agent.data.mqtt.SessionRuntime
import ru.restofleet.agent.data.mqtt.SessionSupervisor
import ru.restofleet.agent.data.mqtt.StatusPayloads
import ru.restofleet.agent.data.mqtt.sessionScope
import ru.restofleet.agent.data.lifecycle.TELEMETRY_INTERVAL_MS
import ru.restofleet.agent.data.probes.ProbeLoop
import ru.restofleet.agent.data.shadow.AppliedTelemetryInterval
import ru.restofleet.agent.data.shadow.FileShadowStore
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.diagnostics.DiagnosticsCommands
import ru.restofleet.agent.domain.diagnostics.OnDemandChecks
import ru.restofleet.agent.domain.port.BrokerSession
import ru.restofleet.agent.domain.port.ConnectionMonitor
import ru.restofleet.agent.domain.port.FlushTrigger
import ru.restofleet.agent.domain.port.MessagePublisher
import ru.restofleet.agent.domain.port.PayloadEncoder
import ru.restofleet.agent.domain.port.ShadowStore
import ru.restofleet.agent.domain.probe.ProbeStates
import ru.restofleet.agent.domain.usecase.ApplyDesiredShadow
import ru.restofleet.agent.domain.usecase.EvaluateThresholds
import ru.restofleet.agent.domain.usecase.FlushOutbox
import ru.restofleet.agent.domain.usecase.HandleIncomingCommand
import ru.restofleet.agent.domain.usecase.PublishEvent
import ru.restofleet.agent.domain.usecase.PublishOrBuffer
import ru.restofleet.agent.domain.usecase.PublishPackageChange
import ru.restofleet.agent.domain.usecase.PublishProbeResult
import ru.restofleet.agent.domain.usecase.PublishTelemetry
import ru.restofleet.agent.domain.usecase.RunProbes
import ru.restofleet.shared.kernel.IdGenerator
import ru.restofleet.shared.kernel.UuidIdGenerator
import java.io.File

/**
 * Qualifier процессного scope MQTT-сессии: переживает сервис, чтобы `offline` успел уйти в `onDestroy`; неожиданное
 * исключение в нём не роняет процесс ([sessionScope], решение 175).
 */
val SESSION_SCOPE = named("sessionScope")

/** Qualifier проверок группы MQTT (сейчас M1). */
val MQTT_CHECKS = named("mqttChecks")

private const val TAG = "AgentMqtt"
private const val CONN_ID_LENGTH = 12

/**
 * Проводка Koin MQTT-сессии, досылки и публикации; логики здесь нет. Нужны извне: `Clock`, `AGENT_DISPATCHER`,
 * `CaSource` и `ProbeSchedule` (debug/release в `agent:app`), `ExecuteCommand`, `Outbox`, `ProcessedCommands`,
 * `CredentialStore`, `CredentialChanges`, `DeviceOwnerRole`, `WatchedApps`, `ProbeExecutor`, `CollectTelemetry`,
 * наблюдатели сети и пакетов.
 */
val mqttModule: Module = module {
    single<PayloadEncoder> { JsonPayloadEncoder() }
    single { FileShadowStore(File(androidContext().noBackupFilesDir, "shadow.json")) }
    single<ShadowStore> { get<FileShadowStore>() }
    single(SESSION_SCOPE) { sessionScope(get<CoroutineDispatcher>(AGENT_DISPATCHER)) { Log.w(TAG, it) } }

    single {
        val context = androidContext()
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        val role = get<DeviceOwnerRole>()
        StatusPayloads(
            facts = DeviceFacts(Build.MODEL, Build.MANUFACTURER, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
            agentVersion = version,
            deviceOwner = role::isDeviceOwner,
            bootId = AndroidBootIdSource(
                readBootId = { File("/proc/sys/kernel/random/boot_id").takeIf { it.canRead() }?.readText() },
                bootCount = {
                    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }
                },
            ),
        )
    }
    single {
        MqttBrokerSession(
            credentials = get(),
            caSource = get(),
            changes = get(),
            linkFactory = MqttLinkFactory { settings, trust, configs ->
                HiveMqLink(settings, trust, configs) { Log.i(TAG, it) }
            },
            ids = IdGenerator { "k-" + UuidIdGenerator().newId().take(CONN_ID_LENGTH) },
            clock = get(),
            statusPayloads = get(),
            scope = get(SESSION_SCOPE),
            log = { Log.i(TAG, it) },
        )
    }
    single<BrokerSession> { get<MqttBrokerSession>() }
    single<MessagePublisher> { get<MqttBrokerSession>() }
    // Экран статуса видит сессию через порт (состояние, connId, код ошибки)
    single<ConnectionMonitor> { SessionConnectionMonitor(get<MqttBrokerSession>().status) }

    // Постановка в outbox при живой связи будит досылку сразу (реш. 203)
    single { ConflatedFlushTrigger() }
    single<FlushTrigger> { get<ConflatedFlushTrigger>() }
    single { PublishOrBuffer(get(), get(), get(), get<FlushTrigger>()) { Log.w(TAG, it) } }
    single { FlushOutbox(get(), get(), log = { Log.w(TAG, it) }) }
    single { HandleIncomingCommand(get(), get(), get(), get(), get(), get()) }
    single { ApplyDesiredShadow(get(), get(), get(), get()) }
    single { PublishTelemetry(get(), get(), get()) }
    single { PublishEvent(get(), get(), get()) }
    single { PublishPackageChange(get(), get()) }
    single { EvaluateThresholds(get(), get()) }

    single {
        SessionSupervisor(
            scope = get(SESSION_SCOPE),
            session = get(),
            connectedEpoch = get<MqttBrokerSession>().connectedEpoch,
            handleCommand = get(),
            applyDesired = get(),
            flush = get(),
            outbox = get(),
            flushRequests = get<ConflatedFlushTrigger>().requests,
            log = { Log.i(TAG, it) },
        )
    }
    single {
        EventPump(
            scope = get(SESSION_SCOPE),
            networkChanges = { get<NetworkChangeObserver>().changes() },
            packageEvents = { watched -> get<PackageEventsObserver>().events(watched) },
            watched = get(),
            publishEvent = get(),
            publishPackageChange = get(),
            clock = get(),
            log = { Log.i(TAG, it) },
        )
    }
    single { PublishProbeResult(get(), get(), get()) }
    // Автоматы up ↔ down целей: один на процесс, их читает и экран статуса
    single { ProbeStates() }
    single { RunProbes(get(), get(), get(), get()) }
    // Минимальный интервал проб (ProbeSchedule) задаёт composition root: debug — стенд 5 с, release — 30 с
    single {
        ProbeLoop(
            scope = get(SESSION_SCOPE),
            shadow = get(),
            run = { target -> get<RunProbes>()(listOf(target)) },
            schedule = get(),
            log = { Log.i(TAG, it) },
        )
    }
    // run_probe и collect_diagnostics (FR-AG-62, 64): исполнитель команд ExecuteCommand получает их через порт
    single<OnDemandChecks> { DiagnosticsCommands(get(), get(), get(), get(), get()) }
    single { AppliedTelemetryInterval(get(), TELEMETRY_INTERVAL_MS) }
    single { SessionRuntime(get(), get(), get(), get(), get()) { Log.i(TAG, it) } }

    single<List<CapabilityCheck>>(MQTT_CHECKS) { listOf(MqttSessionCheck(get<MqttBrokerSession>().status, get())) }
}
