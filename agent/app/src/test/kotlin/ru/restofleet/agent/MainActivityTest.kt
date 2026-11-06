package ru.restofleet.agent

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.loadKoinModules
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.agent.data.lifecycle.ServiceStarter

@RunWith(RobolectricTestRunner::class)
@Config(application = RestoFleetApp::class)
class MainActivityTest {
    private val reasons = mutableListOf<String>()

    @Before
    fun setUp() {
        loadKoinModules(module { single<ServiceStarter> { ServiceStarter { reasons += it } } })
    }

    @After
    fun tearDown() = stopKoin()

    @Test
    fun openingTheScreenEnsuresServiceRunning() {
        Robolectric.buildActivity(MainActivity::class.java).setup()

        // сторож WorkManager в Robolectric выполняется сразу и добавляет свою причину
        assertThat(reasons).contains(MainActivity.REASON_ACTIVITY)
    }
}
