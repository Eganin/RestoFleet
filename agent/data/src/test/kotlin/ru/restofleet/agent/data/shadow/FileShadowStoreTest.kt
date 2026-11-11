package ru.restofleet.agent.data.shadow

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        probes = listOf(ProbeTarget("api", "https://api.example/health", 5000, 60, 4, true, expectedStatus = 204)),
        thresholds = Thresholds(15, 50.5, 7),
    )

    private fun file() = File(tmp.root, "shadow.json")

    @Test
    fun fileWrittenBeforeExpectedStatusIsStillReadable() = runTest {
        // Файл тени MVP (без поля expectedStatus) после обновления агента не теряется
        file().writeText(
            """{"version":5,"telemetryIntervalSec":30,"watchedApps":[],""" +
                """"probes":[{"id":"api","url":"https://a.example/","timeoutMs":5000,"intervalSec":30,""" +
                """"failThreshold":3,"reference":false}],"batteryLowPct":20,"tempHighC":45.0,"fgAbsentMin":10}""",
        )

        val loaded = FileShadowStore(file()).load()

        assertThat(loaded!!.probes.single().expectedStatus).isNull()
        assertThat(loaded.version).isEqualTo(5)
    }

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

    @Test
    fun observeEmitsTheStoredShadowFirstAndEverySaveAfterIt() = runTest {
        val store = FileShadowStore(file())
        store.save(shadow)
        val seen = mutableListOf<DesiredShadow?>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { store.observe().toList(seen) }

        store.save(shadow.copy(version = 13))

        assertThat(seen.map { it?.version }).containsExactly(12L, 13L).inOrder()
        job.cancel()
    }

    @Test
    fun observeOfAnEmptyStoreEmitsNull() = runTest {
        val first = FileShadowStore(file()).observe().first()

        assertThat(first).isNull()
    }
}
