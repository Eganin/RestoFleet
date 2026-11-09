package ru.restofleet.agent.data.outbox

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RoomOutboxTest {
    private lateinit var db: AgentDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun msg(kind: MessageKind, tag: Int) = OutgoingMessage(
        kind = kind,
        payload = byteArrayOf(tag.toByte()),
        qos = 1,
        createdAt = tag.toLong(),
    )

    private fun outbox(capacity: Int = 5) = RoomOutbox(db, capacity)

    @Test
    fun `peek returns events before telemetry and FIFO within a priority`() = runBlocking {
        val outbox = outbox()
        outbox.enqueue(msg(MessageKind.TELEMETRY, 1))
        outbox.enqueue(msg(MessageKind.EVENT, 2))
        outbox.enqueue(msg(MessageKind.PROBE, 3))
        outbox.enqueue(msg(MessageKind.COMMAND_RESULT, 4))
        outbox.enqueue(msg(MessageKind.TELEMETRY, 5))

        val tags = outbox.peek(10).map { it.second.createdAt }

        assertThat(tags).containsExactly(2L, 4L, 3L, 1L, 5L).inOrder()
    }

    @Test
    fun `peek by kind returns only that kind in FIFO order and honours limit`() = runBlocking {
        val outbox = outbox()
        outbox.enqueue(msg(MessageKind.EVENT, 1))
        outbox.enqueue(msg(MessageKind.COMMAND_RESULT, 2))
        outbox.enqueue(msg(MessageKind.TELEMETRY, 3))
        outbox.enqueue(msg(MessageKind.COMMAND_RESULT, 4))
        outbox.enqueue(msg(MessageKind.COMMAND_RESULT, 5))

        assertThat(outbox.peek(10, MessageKind.COMMAND_RESULT).map { it.second.createdAt })
            .containsExactly(2L, 4L, 5L).inOrder()
        assertThat(outbox.peek(2, MessageKind.COMMAND_RESULT).map { it.second.createdAt })
            .containsExactly(2L, 4L).inOrder()
        assertThat(outbox.peek(10, MessageKind.COMMAND_RESULT).first().second.kind)
            .isEqualTo(MessageKind.COMMAND_RESULT)
        assertThat(outbox.peek(10, MessageKind.PROBE)).isEmpty()
        assertThat(outbox.size()).isEqualTo(5)
    }

    @Test
    fun `peek honours limit and does not remove anything`() = runBlocking {
        val outbox = outbox()
        repeat(4) { outbox.enqueue(msg(MessageKind.EVENT, it)) }

        val first = outbox.peek(2)
        val second = outbox.peek(2)

        assertThat(first).hasSize(2)
        assertThat(second).isEqualTo(first)
        assertThat(outbox.size()).isEqualTo(4)
    }

    @Test
    fun `message survives the round trip with all fields`() = runBlocking {
        val original = OutgoingMessage(
            kind = MessageKind.COMMAND_RESULT,
            payload = byteArrayOf(1, 2, 3, 0, -1),
            qos = 1,
            retain = true,
            correlationData = byteArrayOf(9, 8),
            responseTopic = "rf/v1/d/dev-1/cmd/res",
            createdAt = 1_700_000_000_123,
        )
        val outbox = outbox()

        outbox.enqueue(original)

        assertThat(outbox.peek(1).single().second).isEqualTo(original)
    }

    @Test
    fun `null optional fields stay null`() = runBlocking {
        val original = msg(MessageKind.STATUS, 7)
        val outbox = outbox()

        outbox.enqueue(original)

        val restored = outbox.peek(1).single().second
        assertThat(restored.correlationData).isNull()
        assertThat(restored.responseTopic).isNull()
    }

    @Test
    fun `overflow evicts the oldest record of the lowest priority`() = runBlocking {
        val outbox = outbox(capacity = 5)
        outbox.enqueue(msg(MessageKind.EVENT, 1))
        outbox.enqueue(msg(MessageKind.TELEMETRY, 2))
        outbox.enqueue(msg(MessageKind.TELEMETRY, 3))
        outbox.enqueue(msg(MessageKind.PROBE, 4))
        outbox.enqueue(msg(MessageKind.TELEMETRY, 5))

        outbox.enqueue(msg(MessageKind.EVENT, 6))

        assertThat(outbox.size()).isEqualTo(5)
        assertThat(outbox.peek(10).map { it.second.createdAt }).containsExactly(1L, 6L, 4L, 3L, 5L).inOrder()
    }

    @Test
    fun `events are evicted only after telemetry and probes are gone`() = runBlocking {
        val outbox = outbox(capacity = 3)
        outbox.enqueue(msg(MessageKind.EVENT, 1))
        outbox.enqueue(msg(MessageKind.EVENT, 2))
        outbox.enqueue(msg(MessageKind.EVENT, 3))

        outbox.enqueue(msg(MessageKind.EVENT, 4))

        assertThat(outbox.peek(10).map { it.second.createdAt }).containsExactly(2L, 3L, 4L).inOrder()
    }

    @Test
    fun `new telemetry does not push out events when the queue is full of events`() = runBlocking {
        val outbox = outbox(capacity = 2)
        outbox.enqueue(msg(MessageKind.EVENT, 1))
        outbox.enqueue(msg(MessageKind.EVENT, 2))

        outbox.enqueue(msg(MessageKind.TELEMETRY, 3))

        assertThat(outbox.peek(10).map { it.second.createdAt }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `remove deletes only the given ids and ignores unknown ones`() = runBlocking {
        val outbox = outbox()
        repeat(4) { outbox.enqueue(msg(MessageKind.EVENT, it)) }
        val ids = outbox.peek(10).map { it.first }

        outbox.remove(listOf(ids[1], ids[2], 987_654L))

        assertThat(outbox.peek(10).map { it.first }).containsExactly(ids[0], ids[3]).inOrder()
    }

    @Test
    fun `remove with an empty list changes nothing`() = runBlocking {
        val outbox = outbox()
        outbox.enqueue(msg(MessageKind.EVENT, 1))

        outbox.remove(emptyList())

        assertThat(outbox.size()).isEqualTo(1)
    }

    @Test
    fun `remove copes with more ids than the SQLite variable limit`() = runBlocking {
        val outbox = RoomOutbox(db, capacity = 5_000)
        db.withTransaction { repeat(2_500) { outbox.enqueue(msg(MessageKind.TELEMETRY, it)) } }
        val ids = outbox.peek(5_000).map { it.first }

        outbox.remove(ids)

        assertThat(outbox.size()).isEqualTo(0)
    }

    @Test
    fun `a broken flush loses nothing that was not removed`() = runBlocking {
        val outbox = outbox()
        repeat(5) { outbox.enqueue(msg(MessageKind.EVENT, it)) }
        val batch = outbox.peek(3)

        outbox.remove(batch.take(2).map { it.first })

        val rest = outbox.peek(10)
        assertThat(rest.map { it.second.createdAt }).containsExactly(2L, 3L, 4L).inOrder()
        assertThat(rest.first().first).isEqualTo(batch[2].first)
    }

    @Test
    fun `ten thousand and one enqueues leave exactly ten thousand records`() = runBlocking {
        val outbox = RoomOutbox(db)

        db.withTransaction { repeat(10_001) { outbox.enqueue(msg(MessageKind.TELEMETRY, it % 100)) } }

        assertThat(outbox.size()).isEqualTo(10_000)
    }

    @Test
    fun `ten thousand and one enqueues drop the first record`() = runBlocking {
        val outbox = RoomOutbox(db)
        outbox.enqueue(msg(MessageKind.TELEMETRY, 0).copy(createdAt = -1))

        db.withTransaction { repeat(10_000) { outbox.enqueue(msg(MessageKind.TELEMETRY, 1)) } }

        assertThat(outbox.peek(1).single().second.createdAt).isEqualTo(1L)
    }

    @Test
    fun `non positive capacity is rejected`() {
        val failure = runCatching { RoomOutbox(db, capacity = 0) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }
}
