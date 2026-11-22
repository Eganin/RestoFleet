package ru.restofleet.server.data.mqtt

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.CompletableFuture

/** Остаток V-1 (реш. 426): отказ SUBACK повторяется по таймеру на том же соединении, а не ждёт переподключения. */
class ResubscriptionTest {
    private val answers = ArrayDeque<CompletableFuture<Int>>()
    private val attempts = mutableListOf<List<String>>()
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private var connected = true
    private var active = 0

    private val sut = Resubscription(
        subscribe = { attempts += it; answers.removeFirst() },
        connected = { connected },
        onActive = { active++ },
        retryMs = 5_000,
        schedule = { ms, task -> timers += ms to task },
    )

    private fun fire() = timers.removeFirst().second()

    @Test
    fun rejectedSubackIsRetriedOnTheTimerUntilAccepted() {
        answers += CompletableFuture.completedFuture(1)
        answers += CompletableFuture.failedFuture(IllegalStateException("timeout"))
        answers += CompletableFuture.completedFuture(0)

        sut.onConnected(listOf("a", "b"))
        assertThat(active).isEqualTo(0)
        assertThat(timers.single().first).isEqualTo(5_000)

        fire()
        fire()

        assertThat(attempts).hasSize(3)
        assertThat(active).isEqualTo(1)
        assertThat(timers).isEmpty()
    }

    @Test
    fun retryOfAnOldConnectionOrWhileDisconnectedDoesNothing() {
        answers += CompletableFuture.completedFuture(1)
        sut.onConnected(listOf("a"))
        answers += CompletableFuture.completedFuture(0)
        sut.onConnected(listOf("a"))
        assertThat(active).isEqualTo(1)

        fire()
        assertThat(attempts).hasSize(2)

        answers += CompletableFuture.completedFuture(1)
        sut.onConnected(listOf("a"))
        connected = false
        fire()
        assertThat(attempts).hasSize(3)
    }

    @Test
    fun withoutFiltersNothingIsSubscribed() {
        sut.onConnected(emptyList())
        assertThat(attempts).isEmpty()
    }
}
