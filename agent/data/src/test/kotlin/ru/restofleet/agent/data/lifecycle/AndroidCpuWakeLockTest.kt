package ru.restofleet.agent.data.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import ru.restofleet.shared.kernel.Clock
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidCpuWakeLockTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lock = AndroidCpuWakeLock(context)

    @Test
    fun notHeldUntilRequested() {
        assertThat(lock.isHeld).isFalse()
    }

    @Test
    fun acquireTakesNamedNonReferenceCountedPartialWakeLock() {
        lock.acquire(600_000)

        val latest = ShadowPowerManager.getLatestWakeLock()
        assertThat(latest.isHeld).isTrue()
        assertThat(lock.isHeld).isTrue()
        val shadow = shadowOf(latest)
        assertThat(shadow.tag).isEqualTo("RestoFleet:agent")
        assertThat(shadow.isReferenceCounted).isFalse()
    }

    @Test
    fun repeatedAcquireAndRenewAreNotReferenceCounted() {
        val lease = lock.acquire(600_000)
        lease.renew(600_000)
        lease.renew(600_000)

        lease.release()

        assertThat(lock.isHeld).isFalse()
    }

    @Test
    fun releaseTwiceIsHarmless() {
        val lease = lock.acquire(600_000)
        lease.release()
        lease.release()

        assertThat(lock.isHeld).isFalse()
    }

    @Test
    fun supersededLeaseCannotReleaseOrRenewTheNewOwnersLock() {
        val old = lock.acquire(600_000)
        val new = lock.acquire(600_000)

        old.release()

        assertThat(lock.isHeld).isTrue()
        new.release()
        assertThat(lock.isHeld).isFalse()
        old.renew(600_000) // устаревший lease замок не забирает
        assertThat(lock.isHeld).isFalse()
    }

    @Test
    fun lateCompletionOfStoppedCycleDoesNotReleaseTheLockOfTheNewCycle() = runTest {
        val clock = Clock { 1L }
        fun TestScope.cycle(name: String) = AgentCycle(
            TestScope(testScheduler),
            TelemetryLoop(1_000, {}),
            LifecycleJournal(File(tmp.root, name), clock),
            StandardTestDispatcher(testScheduler),
            lock,
        )
        val old = cycle("old")
        val new = cycle("new")
        try {
            old.start("a")
            runCurrent()

            old.stop() // отмена старого цикла ещё не обработана диспетчером
            new.start("b") // новый цикл (другой экземпляр сервиса) берёт тот же замок
            runCurrent() // только теперь завершается корутина старого цикла

            assertThat(lock.isHeld).isTrue()
        } finally {
            new.stop() // иначе при падении утверждения runTest крутит бесконечный цикл в виртуальном времени
            old.stop()
        }
        runCurrent()
        assertThat(lock.isHeld).isFalse()
    }

    @Test
    fun cycleThatDiesByItselfReleasesItsLock() = runTest {
        val unhandled = mutableListOf<Throwable>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cycle = AgentCycle(
            CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> unhandled += e }),
            TelemetryLoop(1_000, { throw OutOfMemoryError("oom") }),
            LifecycleJournal(File(tmp.root, "dies"), Clock { 1L }),
            dispatcher,
            lock,
        )

        cycle.start("a")
        assertThat(lock.isHeld).isTrue()
        runCurrent()

        assertThat(unhandled.single()).isInstanceOf(OutOfMemoryError::class.java)
        assertThat(lock.isHeld).isFalse()
    }
}
