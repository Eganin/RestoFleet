package ru.restofleet.agent.data.shadow

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.restofleet.agent.domain.model.DesiredShadow
import ru.restofleet.agent.domain.model.ProbeTarget
import ru.restofleet.agent.domain.model.Thresholds
import java.io.File

class FileShadowStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val shadow = DesiredShadow(
        version = 12,
        telemetryIntervalSec = 45,
        watchedApps = listOf("com.android.chrome", "ru.x"),
        probes = listOf(ProbeTarget("api", "https://api.example/health", 5000, 60, 4, true)),
        thresholds = Thresholds(15, 50.5, 7),
    )

    private fun file() = File(tmp.root, "shadow.json")

    @Test
    fun emptyStoreHasNothingApplied() = runTest {
        val store = FileShadowStore(file())

        assertThat(store.appliedVersion()).isNull()
        assertThat(store.load()).isNull()
    }

    @Test
    fun savedShadowSurvivesANewInstanceLikeAProcessRestart() = runTest {
        FileShadowStore(file()).save(shadow)

        val reopened = FileShadowStore(file())

        assertThat(reopened.appliedVersion()).isEqualTo(12)
        assertThat(reopened.load()).isEqualTo(shadow)
    }

    @Test
    fun newerSaveReplacesTheOlderOne() = runTest {
        val store = FileShadowStore(file())
        store.save(shadow)

        store.save(shadow.copy(version = 13, telemetryIntervalSec = 20))

        assertThat(store.appliedVersion()).isEqualTo(13)
        assertThat(store.load()!!.telemetryIntervalSec).isEqualTo(20)
    }

    @Test
    fun corruptFileMeansNothingApplied() = runTest {
        file().writeText("{ not json")
        val store = FileShadowStore(file())

        assertThat(store.appliedVersion()).isNull()
        assertThat(store.load()).isNull()
    }

    @Test
    fun saveLeavesNoTemporaryFileBehind() = runTest {
        FileShadowStore(file()).save(shadow)

        assertThat(tmp.root.list()!!.toList()).containsExactly("shadow.json")
    }
}
