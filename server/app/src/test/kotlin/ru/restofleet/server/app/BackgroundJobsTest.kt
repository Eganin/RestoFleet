package ru.restofleet.server.app

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.app.serve.BackendRuntime
import ru.restofleet.server.app.serve.IngestLifecycle
import ru.restofleet.server.app.serve.PeriodicJob
import ru.restofleet.server.app.serve.launchPeriodic
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundJobsTest {

    @Test
    fun `periodic job runs every period and survives a failing run`() = runTest {
        var runs = 0
        val job = PeriodicJob("expire_commands", 5_000) {
            runs++
            if (runs == 2) error("database down") else 1
        }

        backgroundScope.launchPeriodic(job)
        advanceTimeBy(4_999)
        runCurrent()
        val beforeFirst = runs
        advanceTimeBy(15_001)
        runCurrent()

        assertThat(beforeFirst).isEqualTo(0)
        assertThat(runs).isEqualTo(4)
    }

    @Test
    fun `period must be positive`() {
        assertFailsWith<IllegalArgumentException> { PeriodicJob("x", 0) { 0 } }
    }

    @Test
    fun `stop halts ingest first, then the periodic jobs`() = runTest {
        val events = mutableListOf<String>()
        val ingest = object : IngestLifecycle {
            override fun start(scope: CoroutineScope): Job = scope.launch {
                events += "ingest started"
                awaitCancellation()
            }

            override suspend fun stop() {
                events += "ingest stopped"
            }
        }
        var ticks = 0
        val runtime = BackendRuntime(backgroundScope, ingest, listOf(PeriodicJob("tick", 1_000) { ticks++ })) {
            events += "fatal"
        }

        runtime.start()
        advanceTimeBy(3_500)
        runCurrent()
        val ticksBeforeStop = ticks
        val stopper = launch { runtime.stop() }
        runCurrent()
        stopper.cancel()
        advanceTimeBy(5_000)
        runCurrent()

        assertThat(ticksBeforeStop).isEqualTo(3)
        assertThat(ticks).isEqualTo(3)
        assertThat(events).containsAtLeast("ingest started", "ingest stopped").inOrder()
        assertThat(events).doesNotContain("fatal")
    }

    @Test
    fun `failed ingest is fatal, cancelled ingest is not`() = runTest {
        val fatal = mutableListOf<Throwable>()
        val failing = object : IngestLifecycle {
            override fun start(scope: CoroutineScope): Job =
                scope.launch { throw IllegalStateException("SUBACK rejected") }

            override suspend fun stop() = Unit
        }
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job() + CoroutineExceptionHandler { _, _ -> })

        BackendRuntime(scope, failing, emptyList()) { fatal += it }.start()
        runCurrent()

        assertThat(fatal.single()).isInstanceOf(IllegalStateException::class.java)
    }
}
