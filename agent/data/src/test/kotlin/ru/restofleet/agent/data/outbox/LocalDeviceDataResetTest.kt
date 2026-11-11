package ru.restofleet.agent.data.outbox

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.agent.data.shadow.FileShadowStore
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.MessageKind
import ru.restofleet.agent.domain.model.OutgoingMessage
import ru.restofleet.agent.domain.model.Thresholds
import ru.restofleet.shared.shadow.VersionDecision
import java.io.File

/** R-3: регистрация под другим `deviceId` стирает тень, outbox и журнал команд прежнего устройства. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LocalDeviceDataResetTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AgentDatabase
    private lateinit var shadow: FileShadowStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).build()
        shadow = FileShadowStore(File(tmp.root, "shadow.json"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `previous device data is gone and desired v1 of the new device is applied as newer`() = runBlocking {
        val outbox = RoomOutbox(db, capacity = 10)
        val processed = RoomProcessedCommands(db)
        outbox.enqueue(OutgoingMessage(MessageKind.TELEMETRY, byteArrayOf(1), qos = 1, createdAt = 1))
        outbox.enqueue(OutgoingMessage(MessageKind.EVENT, byteArrayOf(2), qos = 1, createdAt = 2))
        processed.markProcessed("c-old")
        shadow.save(DesiredShadow(12, 45, listOf("ru.x"), emptyList(), Thresholds(20, 45.0, 10)))

        LocalDeviceDataReset(db, shadow).clear()

        assertThat(outbox.size()).isEqualTo(0)
        assertThat(processed.isProcessed("c-old")).isFalse()
        assertThat(shadow.load()).isNull()
        assertThat(shadow.observe().first()).isNull()
        // Retained desired v1 нового устройства больше не отбрасывается как старее v12 прежнего
        assertThat(VersionDecision.of(shadow.appliedVersion(), 1)).isEqualTo(VersionDecision.APPLY_NEWER)
    }

    @Test
    fun `clearing an empty store is harmless`() = runBlocking {
        LocalDeviceDataReset(db, shadow).clear()

        assertThat(RoomOutbox(db, capacity = 10).size()).isEqualTo(0)
        assertThat(shadow.load()).isNull()
    }
}
