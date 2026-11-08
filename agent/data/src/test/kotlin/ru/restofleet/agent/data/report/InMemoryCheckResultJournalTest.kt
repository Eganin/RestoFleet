package ru.restofleet.agent.data.report

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.agent.domain.capability.CheckId
import ru.restofleet.agent.domain.capability.CheckResult
import ru.restofleet.agent.domain.capability.CheckStatus
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InMemoryCheckResultJournalTest {
    private fun result(id: CheckId, status: CheckStatus) = CheckResult(id, status, emptyMap(), null, 1L)

    @Test
    fun startsEmpty() {
        assertThat(InMemoryCheckResultJournal().recorded()).isEmpty()
    }

    @Test
    fun keepsTheLatestResultPerCheck() {
        val journal = InMemoryCheckResultJournal()
        journal.record(result(CheckId.C1, CheckStatus.UNAVAILABLE))
        journal.record(result(CheckId.C4, CheckStatus.OK))
        journal.record(result(CheckId.C1, CheckStatus.OK))

        assertThat(journal.recorded()).containsExactly(
            CheckId.C1, result(CheckId.C1, CheckStatus.OK),
            CheckId.C4, result(CheckId.C4, CheckStatus.OK),
        )
    }

    @Test
    fun snapshotIsNotAffectedByLaterRecords() {
        val journal = InMemoryCheckResultJournal()
        journal.record(result(CheckId.C1, CheckStatus.OK))
        val snapshot = journal.recorded()

        journal.record(result(CheckId.C2, CheckStatus.OK))

        assertThat(snapshot.keys).containsExactly(CheckId.C1)
    }

    @Test
    fun recordsFromManyThreadsAreAllKept() {
        val journal = InMemoryCheckResultJournal()
        val pool = Executors.newFixedThreadPool(4)
        CheckId.entries.forEach { id -> pool.execute { journal.record(result(id, CheckStatus.OK)) } }
        pool.shutdown()
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue()

        assertThat(journal.recorded().keys).containsExactlyElementsIn(CheckId.entries)
    }
}
