package ru.restofleet.agent

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.agent.data.di.LIFECYCLE_CHECKS
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
import ru.restofleet.agent.domain.port.CapabilityReportWriter
import ru.restofleet.agent.domain.usecase.CollectTelemetry
import ru.restofleet.agent.domain.usecase.ExportCapabilityReport
import ru.restofleet.agent.domain.usecase.PublishCheckResult
import ru.restofleet.agent.presentation.lab.viewmodel.LabViewModel
import ru.restofleet.shared.kernel.Outcome

@RunWith(RobolectricTestRunner::class)
@Config(application = RestoFleetApp::class)
class KoinGraphTest {
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
