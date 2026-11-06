package ru.restofleet.agent

import com.google.common.truth.Truth.assertThat
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
import ru.restofleet.agent.data.lifecycle.ServiceStarter
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.usecase.CollectTelemetry

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
        val lifecycle = koin.get<List<CapabilityCheck>>(LIFECYCLE_CHECKS)
        assertThat(lifecycle.map { it.id.name }).containsExactly("L1", "L2", "L3", "L4").inOrder()
        assertThat(koin.get<List<CapabilityCheck>>(READING_CHECKS)).isNotEmpty()
        assertThat(koin.get<List<CapabilityCheck>>(PROBE_CHECKS)).isNotEmpty()
    }
}
