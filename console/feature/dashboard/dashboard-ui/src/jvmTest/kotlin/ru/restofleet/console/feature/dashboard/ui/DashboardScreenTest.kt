package ru.restofleet.console.feature.dashboard.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.console.core.designsystem.RfTheme
import ru.restofleet.console.feature.dashboard.domain.Dashboard
import ru.restofleet.console.feature.dashboard.domain.ProblemRestaurant
import ru.restofleet.console.feature.dashboard.domain.RecentAlert
import ru.restofleet.console.feature.dashboard.domain.Summary
import ru.restofleet.console.feature.dashboard.ui.viewmodel.mapper.DashboardUiStateMapper
import ru.restofleet.console.feature.dashboard.ui.viewmodel.model.DashboardSession

/** V-1: обзор отрисовывается, когда у строк совпадают устройство, правило и «N мин назад». */
@OptIn(ExperimentalTestApi::class)
class DashboardScreenTest {
    private val now = 10_000_000L

    @Test
    fun `two R-01 of one device in the same minute do not crash the overview`() = runComposeUiTest {
        fun r01(id: String) = RecentAlert(id, "R-01", false, "d1", "Зал", "Химки", null, now - 60_000, emptyMap())
        val problem = ProblemRestaurant("r1", "MSK-01", "Сокол", "R-05", 4, 6, "api", null, null, now, emptyMap())
        val summary = Summary(5, 6, 1, 1, 2, 0, 1, 1, listOf(problem, problem.copy(target = "kds")))
        val state = DashboardUiStateMapper().map(
            DashboardSession(false, Dashboard(summary, listOf(r01("a1"), r01("a2")), now)),
        )
        setContent { RfTheme(dark = false) { DashboardScreen(state, {}) } }

        assertThat(onAllNodesWithText("Зал · Химки").fetchSemanticsNodes()).hasSize(2)
    }
}
