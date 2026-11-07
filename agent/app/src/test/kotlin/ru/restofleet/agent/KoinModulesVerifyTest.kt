package ru.restofleet.agent

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Parcel
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import org.junit.Test
import org.koin.test.verify.definition
import org.koin.test.verify.injectedParameters
import org.koin.test.verify.verify
import ru.restofleet.agent.data.deviceadmin.LegacyLockTaskEntry
import ru.restofleet.agent.data.di.checksModule
import ru.restofleet.agent.data.di.deviceAdminModule
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
        Function0::class, // isDeviceOwner коллектора R10 передаётся лямбдой из composition root
    )

    @Test
    fun appModuleIsComplete() {
        appModule.verify(
            // Long — таймаут проверки со значением по умолчанию, CoroutineScope — scope ViewModel по умолчанию:
            // нужны только appModule и в общий список не входят, чтобы не ослаблять verify других модулей.
            extraTypes = platformTypes + listOf(Long::class, CoroutineScope::class),
            // список проверок собирается в лямбде из трёх именованных списков — по типу Koin его не найдёт
            injections = injectedParameters(
                definition<RunCapabilityChecks>(List::class),
                // ComponentName создаётся напрямую из контекста и класса приёмника; verify смотрит на его конструктор
                definition<ComponentName>(Parcel::class, Class::class),
            ),
        )
    }

    @Test
    fun checksModuleIsCompleteGivenClock() {
        checksModule.verify(extraTypes = platformTypes + Clock::class)
    }

    @Test
    fun deviceAdminModuleIsCompleteGivenAppPorts() {
        // ComponentName и LegacyLockTaskEntry — классы app, их предоставляет appModule
        deviceAdminModule.verify(
            extraTypes = platformTypes + listOf(ComponentName::class, LegacyLockTaskEntry::class),
        )
    }

    @Test
    fun lifecycleModuleIsCompleteGivenClockAndWatchdog() {
        lifecycleModule.verify(extraTypes = platformTypes + listOf(Clock::class, WatchdogSchedule::class))
    }
}
