package ru.restofleet.agent

import com.google.common.truth.Truth.assertThat
import android.content.ComponentName
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.koin.core.error.NoDefinitionFoundException
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import okhttp3.OkHttpClient
import ru.restofleet.agent.data.command.AlarmBeeper
import ru.restofleet.agent.data.command.AndroidAppLauncher
import ru.restofleet.agent.data.command.AndroidCapabilitiesSource
import ru.restofleet.agent.data.command.NotificationUserNotifier
import ru.restofleet.agent.data.lifecycle.DevicePermissions
import ru.restofleet.agent.data.lifecycle.WizardPermissions
import ru.restofleet.agent.domain.command.Command
import ru.restofleet.agent.domain.command.CommandOutcome
import ru.restofleet.agent.domain.port.AppLauncher
import ru.restofleet.agent.domain.port.Beeper
import ru.restofleet.agent.domain.port.CapabilitiesSource
import ru.restofleet.agent.domain.port.UserNotifier
import ru.restofleet.agent.domain.usecase.ExecuteCommand
import ru.restofleet.agent.data.deviceadmin.DpmDeviceAdmin
import ru.restofleet.agent.data.deviceadmin.LegacyLockTaskEntry
import ru.restofleet.agent.data.di.ADMIN_COMPONENT
import ru.restofleet.agent.data.di.APK_HTTP_CLIENT
import ru.restofleet.agent.data.di.LIFECYCLE_CHECKS
import ru.restofleet.agent.data.di.PROBE_HTTP_CLIENT
import ru.restofleet.agent.data.di.PROBE_CHECKS
import ru.restofleet.agent.data.di.READING_CHECKS
import ru.restofleet.agent.data.lifecycle.AndroidCpuWakeLock
import ru.restofleet.agent.data.lifecycle.CpuWakeLock
import ru.restofleet.agent.data.lifecycle.HeartbeatLog
import ru.restofleet.agent.data.lifecycle.ServiceStarter
import ru.restofleet.agent.data.lifecycle.TelemetryLoop
import ru.restofleet.agent.data.report.JsonCapabilityReportWriter
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.agent.domain.error.MqttNotConfigured
import ru.restofleet.agent.domain.port.DeviceAdmin
import ru.restofleet.agent.domain.port.CapabilityReportWriter
import ru.restofleet.agent.domain.usecase.CollectTelemetry
import ru.restofleet.agent.domain.usecase.ExportCapabilityReport
import ru.restofleet.agent.domain.usecase.PublishCheckResult
import ru.restofleet.agent.presentation.lab.viewmodel.LabViewModel
import ru.restofleet.shared.kernel.Outcome

@RunWith(RobolectricTestRunner::class)
@Config(application = RestoFleetApp::class)
class KoinGraphTest {
    @get:Rule val workManagerIsolation = WorkManagerIsolationRule()

    @After
    fun tearDown() = stopKoin()

    // verify() не проверяет лямбды single { … }: реальный граф поднимает приложение, резолвим ключевые типы.
    @Test
    fun realGraphResolvesKeyTypes() {
        val koin = GlobalContext.get()

        assertThat(koin.get<CollectTelemetry>()).isNotNull()
        assertThat(koin.get<ServiceStarter>()).isInstanceOf(AndroidServiceStarter::class.java)
        assertThat(koin.get<TelemetryLoop>()).isNotNull()
        assertThat(koin.get<HeartbeatLog>()).isNotNull()
        assertThat(koin.get<AgentNotifications>()).isNotNull()
        assertThat(koin.get<SettingsIntents>()).isNotNull()
        assertThat(koin.get<ForegroundStarter>()).isInstanceOf(ServiceCompatForegroundStarter::class.java)
        assertThat(koin.get<CpuWakeLock>()).isInstanceOf(AndroidCpuWakeLock::class.java)
        val lifecycle = koin.get<List<CapabilityCheck>>(LIFECYCLE_CHECKS)
        assertThat(lifecycle.map { it.id.name }).containsExactly("L1", "L2", "L3", "L4").inOrder()
        assertThat(koin.get<List<CapabilityCheck>>(READING_CHECKS)).isNotEmpty()
        assertThat(koin.get<List<CapabilityCheck>>(PROBE_CHECKS)).isNotEmpty()
    }

    @Test
    fun deviceAdminGraphResolvesWithAppPortsAndQualifiedClients() {
        val koin = GlobalContext.get()

        assertThat(koin.get<DeviceAdmin>()).isInstanceOf(DpmDeviceAdmin::class.java)
        assertThat(koin.get<ComponentName>(ADMIN_COMPONENT))
            .isEqualTo(ComponentName("ru.restofleet.agent", "ru.restofleet.agent.AdminReceiver"))
        // без qualifier'а платформенный ComponentName в графе не регистрируется: чужой single его не подменит
        assertThrows(NoDefinitionFoundException::class.java) { koin.get<ComponentName>() }
        val probe = koin.get<OkHttpClient>(PROBE_HTTP_CLIENT)
        val apk = koin.get<OkHttpClient>(APK_HTTP_CLIENT)
        assertThat(apk).isNotSameInstanceAs(probe)
        assertThat(probe.followRedirects).isTrue() // клиент проб без изменений поведения: настройки OkHttp по умолчанию
        assertThat(apk.followSslRedirects).isFalse()
        assertThat(apk.callTimeoutMillis).isEqualTo(600_000)
        val ids = koin.get<List<CapabilityCheck>>(READING_CHECKS).map { it.id }
        assertThat(ids).containsAtLeast(CheckId.R10, CheckId.R13)
    }

    @Test
    fun executeCommandResolvesWithAllPortsFromTheRealAdapters() {
        val koin = GlobalContext.get()

        assertThat(koin.get<UserNotifier>()).isInstanceOf(NotificationUserNotifier::class.java)
        assertThat(koin.get<AppLauncher>()).isInstanceOf(AndroidAppLauncher::class.java)
        assertThat(koin.get<Beeper>()).isInstanceOf(AlarmBeeper::class.java)
        assertThat(koin.get<CapabilitiesSource>()).isInstanceOf(AndroidCapabilitiesSource::class.java)
        // не Device Owner в Robolectric: команда обычного режима проходит через настоящий граф до портов
        val outcome = runBlocking { koin.get<ExecuteCommand>()(Command.Reboot) }
        assertThat(outcome).isEqualTo(CommandOutcome.Unsupported("requires_device_owner"))
        val ping = runBlocking { koin.get<ExecuteCommand>()(Command.Ping) }
        assertThat(ping).isInstanceOf(CommandOutcome.Succeeded::class.java)
        assertThat(koin.get<WizardPermissions>()).isSameInstanceAs(koin.get<DevicePermissions>())
    }

    @Test
    fun legacyLockTaskEntryPointsAtLockTaskActivityWithoutNestedIntent() {
        val intent = GlobalContext.get().get<LegacyLockTaskEntry>().intent("com.android.chrome", "https://x.test/")

        assertThat(intent.component?.className).isEqualTo(LockTaskActivity::class.java.name)
        assertThat(intent.getStringExtra("pkg")).isEqualTo("com.android.chrome")
        assertThat(intent.getStringExtra("url")).isEqualTo("https://x.test/")
        assertThat(intent.extras!!.keySet()).containsExactly("pkg", "url")
    }

    @Test
    fun labGraphResolvesAndPublicationIsStubbedUntilMqtt() {
        val koin = GlobalContext.get()

        assertThat(koin.get<LabViewModel>()).isNotNull()
        assertThat(koin.get<ExportCapabilityReport>()).isNotNull()
        assertThat(koin.get<CapabilityReportWriter>()).isInstanceOf(JsonCapabilityReportWriter::class.java)
        val result = CheckResult(CheckId.R1, CheckStatus.OK, emptyMap(), null, 1L)
        val outcome = runBlocking { koin.get<PublishCheckResult>()(result) }
        assertThat(outcome).isEqualTo(Outcome.Failure(MqttNotConfigured))
    }
}
