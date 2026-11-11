package ru.restofleet.server.simulator.data

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import ru.restofleet.server.simulator.data.scenario.YamlScenarioLoader
import java.io.File

/**
 * Сценарии эксперимента `tools/experiments/scenarios/<имя>.yaml` (КМ4, R-5): каждый разбирается строгим
 * загрузчиком, имя равно имени файла (на нём держится проверка `run-experiment.sh`, R-20), окно замера не короче
 * двух TTL кэша Basic.
 */
class ExperimentScenariosTest {
    private val files = File(System.getProperty("scenariosDir")).listFiles { f -> f.extension == "yaml" }!!.sorted()

    @Test
    fun `every scenario parses and is named after its file`() {
        assertThat(files.size).isAtLeast(SCENARIOS_KM4)
        files.forEach { file ->
            val scenario = YamlScenarioLoader.load(file.toPath()).scenario
            assertWithMessage(file.name).that(scenario.name).isEqualTo(file.nameWithoutExtension)
            assertWithMessage(file.name).that(scenario.name).matches("[A-Za-z0-9._-]+")
        }
    }

    @Test
    fun `measurement window covers at least two Basic cache TTLs in every experiment scenario`() {
        // R-5 (реш. 310): в окно 240 с не попадала ни одна повторная bcrypt-проверка при N ≤ 1 000, а при N = 5 000 —
        // синхронный всплеск истечения кэша. Smoke-сценарии — проверка цепочки, не замер: их окно не ограничено
        files.filterNot { it.name.startsWith("smoke-") }.forEach { file ->
            val scenario = YamlScenarioLoader.load(file.toPath()).scenario
            assertWithMessage(file.name).that(scenario.durationSec).isAtLeast(2 * BASIC_CACHE_TTL_SEC)
        }
    }

    private companion object {
        /** 16 прогонов КМ4 + 2 smoke. */
        const val SCENARIOS_KM4 = 18

        /** TTL кэша Basic бэкенда (`DataConfig.DEFAULT_AUTH_CACHE_TTL_MS`, реш. 213): 5 мин. */
        const val BASIC_CACHE_TTL_SEC = 300
    }
}
