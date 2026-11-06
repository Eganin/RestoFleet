package ru.restofleet.agent.data.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.restofleet.shared.kernel.Clock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LifecycleStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun forContextStoresFilesInNoBackupDir() {
        val storage = LifecycleStorage.forContext(context, Clock { 5L })

        storage.heartbeatLog().append()
        storage.journal().recordBoot()

        val files = context.noBackupFilesDir.list().orEmpty().toList()
        assertThat(files).containsAtLeast(LifecycleStorage.HEARTBEAT_FILE, LifecycleStorage.JOURNAL_FILE)
        assertThat(storage.heartbeatLog().timestamps()).containsExactly(5L)
        assertThat(storage.journal().lastBootAt()).isEqualTo(5L)
    }

    @Test
    fun telemetryIntervalIsKeepaliveThirtySeconds() {
        assertThat(TELEMETRY_INTERVAL_MS).isEqualTo(30_000L)
    }
}
