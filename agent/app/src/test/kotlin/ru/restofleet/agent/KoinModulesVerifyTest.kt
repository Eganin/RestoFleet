package ru.restofleet.agent

import android.app.Application
import android.content.Context
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import org.koin.test.verify.definition
import org.koin.test.verify.injectedParameters
import org.koin.test.verify.verify
import ru.restofleet.agent.data.di.checksModule
import ru.restofleet.agent.data.platform.FreeFallDetector
import ru.restofleet.agent.data.di.lifecycleModule
import ru.restofleet.agent.data.lifecycle.WatchdogSchedule
import ru.restofleet.agent.di.appModule
import ru.restofleet.agent.domain.usecase.RunCapabilityChecks
import ru.restofleet.shared.kernel.Clock
import java.io.File

class KoinModulesVerifyTest {
    // Типы, которые модули создают inline внутри лямбд или получают из платформы, а не регистрируют.
    private val platformTypes = listOf(
        Context::class,
        Application::class,
        WorkerParameters::class,
        File::class,
        FreeFallDetector::class,
        Function1::class, // лямбды tick/onError/log с значениями по умолчанию
        Long::class, // таймаут проверки со значением по умолчанию
        CoroutineScope::class, // scope ViewModel со значением по умолчанию
    )

    @Test
    fun appModuleIsComplete() {
        appModule.verify(
            extraTypes = platformTypes,
            // список проверок собирается в лямбде из трёх именованных списков — по типу Koin его не найдёт
            injections = injectedParameters(definition<RunCapabilityChecks>(List::class)),
        )
    }

    @Test
    fun checksModuleIsCompleteGivenClock() {
        checksModule.verify(extraTypes = platformTypes + Clock::class)
    }

    @Test
    fun lifecycleModuleIsCompleteGivenClockAndWatchdog() {
        lifecycleModule.verify(extraTypes = platformTypes + listOf(Clock::class, WatchdogSchedule::class))
    }
}
