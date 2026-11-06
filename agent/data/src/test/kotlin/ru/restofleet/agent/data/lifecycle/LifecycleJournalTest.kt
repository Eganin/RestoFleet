package ru.restofleet.agent.data.lifecycle

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.restofleet.shared.kernel.Clock
import java.io.File

class LifecycleJournalTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 0L
    private val clock = Clock { now }
    private val file get() = File(tmp.root, "journal.log")

    private fun journal(maxLines: Int = 500) = LifecycleJournal(file, clock, maxLines)

    private fun at(ts: Long, block: () -> Unit) {
        now = ts
        block()
    }

    @Test
    fun recordsEntriesInOrder() {
        val j = journal()
        at(10) { j.recordBoot() }
        at(20) { j.recordStart("boot") }
        at(30) { j.recordStartDenied("watchdog") }
        at(40) { j.recordStop() }

        assertThat(j.entries()).containsExactly(
            LifecycleEntry(LifecycleEntry.Kind.BOOT, 10, null),
            LifecycleEntry(LifecycleEntry.Kind.START, 20, "boot"),
            LifecycleEntry(LifecycleEntry.Kind.START_DENIED, 30, "watchdog"),
            LifecycleEntry(LifecycleEntry.Kind.STOP, 40, null),
        ).inOrder()
    }

    @Test
    fun lastBootIsTheLatest() {
        val j = journal()
        assertThat(j.lastBootAt()).isNull()
        at(10) { j.recordBoot() }
        at(500) { j.recordBoot() }
        at(600) { j.recordStart("boot") }

        assertThat(j.lastBootAt()).isEqualTo(500L)
    }

    @Test
    fun watchdogStartAndDenialAreTrackedSeparately() {
        val j = journal()
        assertThat(j.lastWatchdogStartAt()).isNull()
        assertThat(j.lastWatchdogDeniedAt()).isNull()
        at(10) { j.recordStart("watchdog") }
        at(20) { j.recordStart("manual") }
        at(30) { j.recordStartDenied("watchdog") }
        at(40) { j.recordStartDenied("boot") }

        assertThat(j.lastWatchdogStartAt()).isEqualTo(10L)
        assertThat(j.lastWatchdogDeniedAt()).isEqualTo(30L)
    }

    @Test
    fun reasonWithSeparatorsIsSanitized() {
        val j = journal()
        at(5) { j.recordStart("a|b\nc") }

        val entry = j.entries().single()
        assertThat(entry.reason).isEqualTo("a_b_c")
    }

    @Test
    fun survivesRecreation() {
        at(10) { journal().recordBoot() }

        assertThat(journal().lastBootAt()).isEqualTo(10L)
    }

    @Test
    fun brokenLinesAndTornTailAreIgnored() {
        file.writeText("boot|10\ngarbage\nstart|x|boot\nunknown|5\nstart|20|bo")

        val j = journal()
        assertThat(j.entries()).containsExactly(LifecycleEntry(LifecycleEntry.Kind.BOOT, 10, null))

        at(30) { j.recordStop() }
        assertThat(j.entries().map { it.kind }).containsExactly(
            LifecycleEntry.Kind.BOOT,
            LifecycleEntry.Kind.STOP,
        ).inOrder()
    }

    @Test
    fun keepsOnlyLastMaxLines() {
        val j = journal(maxLines = 3)
        (1..50).forEach { i -> at(i.toLong()) { j.recordStart("r$i") } }

        assertThat(j.entries().map { it.ts }).containsExactly(48L, 49L, 50L).inOrder()
    }
}
