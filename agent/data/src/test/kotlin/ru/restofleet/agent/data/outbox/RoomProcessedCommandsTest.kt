package ru.restofleet.agent.data.outbox

import android.content.Context
import androidx.room.Room
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
class RoomProcessedCommandsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "processed-commands-test.db"
    private lateinit var db: AgentDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        db = open()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    private fun open() = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
        .allowMainThreadQueries()
        .build()

    @Test
    fun `unknown command is not processed`() = runBlocking {
        assertThat(RoomProcessedCommands(db).isProcessed("c-1")).isFalse()
    }

    @Test
    fun `marked command is processed`() = runBlocking {
        val journal = RoomProcessedCommands(db)

        journal.markProcessed("c-1")

        assertThat(journal.isProcessed("c-1")).isTrue()
    }

    @Test
    fun `after 201 marks the first one is forgotten and the last one is kept`() = runBlocking {
        val journal = RoomProcessedCommands(db)

        repeat(201) { journal.markProcessed("c-$it") }

        assertThat(journal.isProcessed("c-0")).isFalse()
        assertThat(journal.isProcessed("c-1")).isTrue()
        assertThat(journal.isProcessed("c-200")).isTrue()
    }

    @Test
    fun `repeated mark is idempotent and does not push others out`() = runBlocking {
        val journal = RoomProcessedCommands(db, capacity = 3)
        journal.markProcessed("a")
        journal.markProcessed("b")
        journal.markProcessed("c")

        repeat(5) { journal.markProcessed("c") }
        journal.markProcessed("a")

        assertThat(journal.isProcessed("a")).isTrue()
        assertThat(journal.isProcessed("b")).isTrue()
        assertThat(journal.isProcessed("c")).isTrue()
    }

    @Test
    fun `repeated mark does not refresh the eviction order`() = runBlocking {
        val journal = RoomProcessedCommands(db, capacity = 2)
        journal.markProcessed("a")
        journal.markProcessed("b")

        journal.markProcessed("a")
        journal.markProcessed("c")

        assertThat(journal.isProcessed("a")).isFalse()
        assertThat(journal.isProcessed("b")).isTrue()
        assertThat(journal.isProcessed("c")).isTrue()
    }

    @Test
    fun `journal and outbox survive reopening the database file`() = runBlocking {
        RoomProcessedCommands(db).markProcessed("c-7")
        RoomOutbox(db, 5).enqueue(OutgoingMessage(MessageKind.EVENT, byteArrayOf(1), qos = 1, createdAt = 42))
        db.close()

        db = open()

        assertThat(RoomProcessedCommands(db).isProcessed("c-7")).isTrue()
        assertThat(RoomOutbox(db, 5).peek(1).single().second.createdAt).isEqualTo(42L)
    }
}
