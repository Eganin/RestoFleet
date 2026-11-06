package ru.restofleet.agent.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.agent.domain.capability.CapabilityCheck
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import ru.restofleet.shared.kernel.Clock
import kotlin.test.assertFailsWith

class RunCapabilityChecksTest {

    private val clock = Clock { NOW }

    private fun check(id: CheckId, body: suspend () -> CheckResult = { ok(id) }) =
        object : CapabilityCheck {
            override val id = id
            override suspend fun run() = body()
        }

    private fun ok(id: CheckId) =
        CheckResult(id, CheckStatus.OK, mapOf("k" to "v"), null, CHECKED_AT)

    @Test
    fun resultsAreOrderedByCheckId() = runTest {
        val run = RunCapabilityChecks(listOf(check(CheckId.R2), check(CheckId.L1), check(CheckId.M1)), clock)

        val results = run(setOf(CheckId.R2, CheckId.L1, CheckId.M1))

        assertThat(results.map { it.id }).containsExactly(CheckId.L1, CheckId.R2, CheckId.M1).inOrder()
        assertThat(results.first().raw).containsExactly("k", "v")
        assertThat(results.first().checkedAt).isEqualTo(CHECKED_AT)
    }

    @Test
    fun nullRunsEveryCheckAndFillsUnregisteredWithNotChecked() = runTest {
        val run = RunCapabilityChecks(listOf(check(CheckId.L1)), clock)

        val results = run()

        assertThat(results).hasSize(CheckId.entries.size)
        assertThat(results.map { it.id }).containsExactlyElementsIn(CheckId.entries).inOrder()
        val l1 = results.single { it.id == CheckId.L1 }
        assertThat(l1.status).isEqualTo(CheckStatus.OK)
        val others = results.filter { it.id != CheckId.L1 }
        assertThat(others.map { it.status }.toSet()).containsExactly(CheckStatus.NOT_CHECKED)
        assertThat(others.map { it.checkedAt }.toSet()).containsExactly(NOW)
    }

    @Test
    fun unregisteredCheckIsNotChecked() = runTest {
        val run = RunCapabilityChecks(emptyList(), clock)

        val result = run(setOf(CheckId.D3)).single()

        assertThat(result.id).isEqualTo(CheckId.D3)
        assertThat(result.status).isEqualTo(CheckStatus.NOT_CHECKED)
        assertThat(result.raw).isEmpty()
        assertThat(result.checkedAt).isEqualTo(NOW)
    }

    @Test
    fun hangingCheckTimesOutAndOthersStillRun() = runTest {
        val run = RunCapabilityChecks(
            listOf(check(CheckId.L1) { delay(Long.MAX_VALUE); ok(CheckId.L1) }, check(CheckId.L2)),
            clock,
        )

        val results = run(setOf(CheckId.L1, CheckId.L2))

        val l1 = results.first { it.id == CheckId.L1 }
        assertThat(l1.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(l1.note).isEqualTo("timeout 10000 ms")
        assertThat(l1.checkedAt).isEqualTo(NOW)
        assertThat(results.first { it.id == CheckId.L2 }.status).isEqualTo(CheckStatus.OK)
    }

    @Test
    fun timeoutNoteUsesConfiguredValue() = runTest {
        val run = RunCapabilityChecks(
            listOf(check(CheckId.L1) { delay(Long.MAX_VALUE); ok(CheckId.L1) }),
            clock,
            timeoutMillis = 250,
        )

        assertThat(run(setOf(CheckId.L1)).single().note).isEqualTo("timeout 250 ms")
    }

    @Test
    fun thrownExceptionBecomesUnavailableWithMessage() = runTest {
        val run = RunCapabilityChecks(
            listOf(check(CheckId.R5) { throw SecurityException("no location") }, check(CheckId.R6)),
            clock,
        )

        val results = run(setOf(CheckId.R5, CheckId.R6))

        val r5 = results.first { it.id == CheckId.R5 }
        assertThat(r5.status).isEqualTo(CheckStatus.UNAVAILABLE)
        assertThat(r5.note).isEqualTo("no location")
        assertThat(r5.checkedAt).isEqualTo(NOW)
        assertThat(results.first { it.id == CheckId.R6 }.status).isEqualTo(CheckStatus.OK)
    }

    @Test
    fun exceptionWithoutMessageUsesClassName() = runTest {
        val run = RunCapabilityChecks(listOf(check(CheckId.R5) { throw IllegalStateException() }), clock)

        assertThat(run(setOf(CheckId.R5)).single().note).isEqualTo("IllegalStateException")
    }

    @Test
    fun filterRunsOnlyRequestedChecks() = runTest {
        val executed = mutableListOf<CheckId>()
        val run = RunCapabilityChecks(
            CheckId.entries.map { id -> check(id) { executed += id; ok(id) } },
            clock,
        )

        val results = run(setOf(CheckId.R1, CheckId.P2))

        assertThat(results.map { it.id }).containsExactly(CheckId.R1, CheckId.P2).inOrder()
        assertThat(executed).containsExactly(CheckId.R1, CheckId.P2)
    }

    @Test
    fun duplicateIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            RunCapabilityChecks(listOf(check(CheckId.L1), check(CheckId.L1)), clock)
        }
    }

    @Test
    fun errorIsNotSwallowed() = runTest {
        val run = RunCapabilityChecks(listOf(check(CheckId.L1) { throw OutOfMemoryError("boom") }), clock)

        assertFailsWith<OutOfMemoryError> { run(setOf(CheckId.L1)) }
    }

    @Test
    fun outerCancellationPropagates() = runTest {
        val started = CompletableDeferred<Unit>()
        val run = RunCapabilityChecks(
            listOf(check(CheckId.L1) { started.complete(Unit); awaitCancellation() }),
            clock,
        )

        val job = async(start = CoroutineStart.UNDISPATCHED) { run(setOf(CheckId.L1)) }
        started.await()
        job.cancel()

        assertFailsWith<kotlinx.coroutines.CancellationException> { job.await() }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val CHECKED_AT = 1_699_999_999_000L
    }
}
