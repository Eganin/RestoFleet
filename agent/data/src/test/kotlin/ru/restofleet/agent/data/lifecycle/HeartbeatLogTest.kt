package ru.restofleet.agent.data.lifecycle

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.restofleet.shared.kernel.Clock
import java.io.File

class HeartbeatLogTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000L
    private val clock = Clock { now }

    private fun log(file: File = File(tmp.root, "hb.log"), maxLines: Int = 5_000) = HeartbeatLog(file, clock, maxLines)

    private fun HeartbeatLog.tick(at: Long) {
        now = at
        append()
    }

    @Test
    fun threeAppendsReturnThreeClockTimestamps() {
        val log = log()
        log.tick(1_000)
        log.tick(31_000)
        log.tick(61_000)

        assertThat(log.timestamps()).containsExactly(1_000L, 31_000L, 61_000L).inOrder()
    }

    @Test
    fun emptyWhenFileMissing() {
        assertThat(log().timestamps()).isEmpty()
    }

    @Test
    fun keepsOnlyLastMaxLines() {
        val log = log(maxLines = 2)
        (1..10).forEach { log.tick(it * 1_000L) }

        assertThat(log.timestamps()).containsExactly(9_000L, 10_000L).inOrder()
    }

    @Test
    fun trimmingShrinksTheFileOnDisk() {
        val file = File(tmp.root, "hb.log")
        val log = log(file, maxLines = 10)
        (1..200).forEach { log.tick(it.toLong()) }

        assertThat(file.readLines().size).isAtMost(20)
        assertThat(log.timestamps()).hasSize(10)
        assertThat(log.timestamps().last()).isEqualTo(200L)
    }

    @Test
    fun survivesRecreationOnSameFile() {
        val file = File(tmp.root, "hb.log")
        log(file).apply {
            tick(1_000)
            tick(2_000)
        }

        val second = log(file)
        second.tick(3_000)

        assertThat(second.timestamps()).containsExactly(1_000L, 2_000L, 3_000L).inOrder()
    }

    @Test
    fun skipsGarbageLinesAndTornTail() {
        val file = File(tmp.root, "hb.log")
        file.writeText("1000\nnot-a-number\n\n2000\n30")

        val log = log(file)
        assertThat(log.timestamps()).containsExactly(1_000L, 2_000L).inOrder()

        log.tick(4_000)
        assertThat(log.timestamps()).containsExactly(1_000L, 2_000L, 4_000L).inOrder()
    }

    @Test
    fun tornTailRepairIsAtomicAndLeavesNoTempFile() {
        val file = File(tmp.root, "hb.log")
        file.writeText("1000\n2000\n30")
        val log = log(file)

        log.tick(4_000)

        assertThat(File(tmp.root, "hb.log.tmp").exists()).isFalse()
        assertThat(file.readText()).isEqualTo("1000\n2000\n4000\n")
    }

    @Test
    fun concurrentAppendsDoNotLoseOrCorruptLines() {
        val log = log(maxLines = 10_000)
        val threads = (1..4).map { t ->
            Thread { repeat(200) { log.tick(t * 1_000L + it) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertThat(log.timestamps()).hasSize(800)
    }
}
