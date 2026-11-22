package ru.restofleet.server.domain.alert

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.restofleet.server.domain.alert.usecase.GetSummary
import ru.restofleet.server.domain.device.DeviceCounts
import ru.restofleet.server.domain.device.DeviceStatus
import ru.restofleet.server.domain.fake.World
import ru.restofleet.server.domain.restaurant.Restaurant
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.DeviceId

/** Сводка «Обзор» (FR-CN-02, реш. 436): выбор главной проблемы ресторана и порядок ресторанов. */
class SummaryBuilderTest {
    private val r1 = restaurant("r-1", "MSK-01")
    private val r2 = restaurant("r-2", "MSK-02")
    private val r3 = restaurant("r-3", "MSK-03")
    private val counts = DeviceCounts(10, 7, 3, 1, mapOf(r1.id to 6, r2.id to 3, r3.id to 1))

    @Test
    fun countsAndEmpty() {
        val summary = SummaryBuilder.build(counts, emptyList(), listOf(r1, r2, r3))
        assertThat(summary).isEqualTo(Summary(7, 10, 3, 1, 0, 0, 0, 0, 3, emptyList()))
    }

    @Test
    fun criticalRuleWinsOverMoreDevicesWithWarning() {
        val alerts = listOf(
            view("a1", RuleId.R01, "d-1", r1, 1), view("a2", RuleId.R01, "d-2", r1, 2),
            view("a3", RuleId.R05, "d-3", r1, 3, target = "api"),
        )
        val problem = SummaryBuilder.build(counts, alerts, listOf(r1)).problems.single()
        assertThat(problem.rule).isEqualTo(RuleId.R05)
        assertThat(problem.count).isEqualTo(1)
        assertThat(problem.devicesTotal).isEqualTo(6)
        assertThat(problem.deviceId?.value).isEqualTo("d-3")
        assertThat(problem.deviceName).isEqualTo("name d-3")
        assertThat(problem.target).isEqualTo("api")
    }

    @Test
    fun severalDevicesOfOneRuleHaveNoSingleDeviceAndKeepEarliestDetails() {
        val alerts = listOf(
            view("a1", RuleId.R05, "d-1", r1, 5, target = "api", details = mapOf("error" to "dns_fail")),
            view("a2", RuleId.R05, "d-2", r1, 3, target = "api", details = mapOf("error" to "timeout")),
            view("a3", RuleId.R05, "d-2", r1, 4, target = "kds"),
        )
        val problem = SummaryBuilder.build(counts, alerts, listOf(r1)).problems.single()
        assertThat(problem.count).isEqualTo(2)
        assertThat(problem.deviceId).isNull()
        assertThat(problem.target).isNull()
        assertThat(problem.since).isEqualTo(3)
        assertThat(problem.details).containsExactly("error", "timeout")
    }

    @Test
    fun restaurantsOrderedBySeverityThenCountThenAgeAndSuppressedIgnored() {
        val alerts = listOf(
            view("a1", RuleId.R01, "d-1", r1, 1), view("a2", RuleId.R01, "d-2", r1, 2),
            view("a3", RuleId.R03, "d-3", r2, 9),
            view("a4", RuleId.R06, "d-4", r3, 0),
            view("a5", RuleId.R05, "d-5", r3, 0, suppressed = true),
            view("a6", RuleId.R02, "d-6", r3, 0, state = AlertState.RESOLVED),
        )
        val summary = SummaryBuilder.build(counts, alerts, listOf(r1, r2, r3))
        assertThat(summary.problems.map { it.restaurant.code }).containsExactly("MSK-02", "MSK-01", "MSK-03").inOrder()
        assertThat(summary.alertsOpen).isEqualTo(4)
        assertThat(summary.alertsCritical).isEqualTo(1)
        assertThat(summary.restaurantsWithProblems).isEqualTo(3)
    }

    @Test
    fun atMostTenRestaurantsAndExactCountsOverride() {
        val many = (1..12).map { restaurant("x-$it", "C-%02d".format(it)) }
        val alerts = many.mapIndexed { i, r -> view("a$i", RuleId.R06, "d-$i", r, i.toLong()) }
        val summary = SummaryBuilder.build(counts, alerts, many, incidentsOpen = 2, counts = AlertCounts(5000, 70))
        assertThat(summary.problems).hasSize(SummaryBuilder.MAX_PROBLEMS)
        assertThat(summary.problems.first().restaurant.code).isEqualTo("C-01")
        assertThat(summary.restaurantsWithProblems).isEqualTo(12)
        assertThat(summary.alertsOpen).isEqualTo(5000)
        assertThat(summary.alertsCritical).isEqualTo(70)
        assertThat(summary.incidentsOpen).isEqualTo(2)
    }

    @Test
    fun alertOfUnknownRestaurantIsSkipped() {
        val ghost = restaurant("ghost", "G")
        val summary = SummaryBuilder.build(counts, listOf(view("a", RuleId.R01, "d-1", ghost, 1)), listOf(r1))
        assertThat(summary.problems).isEmpty()
    }

    @Test
    fun getSummaryReadsRepositories() = runTest {
        val w = World()
        w.clock.now = 10_000_000
        w.restaurant("r-1")
        w.device("d-1", status = DeviceStatus.ONLINE)
        w.device("d-2", status = DeviceStatus.OFFLINE, lastSeenAt = w.clock.now - 10)
        w.device("d-3", status = DeviceStatus.OFFLINE, lastSeenAt = 0)
        w.device("d-4", status = DeviceStatus.DECOMMISSIONED)
        val alert = Alert("a", AlertSubject(RuleId.R01, World.deviceId("d-2")), RestaurantId("r-1"), AlertState.OPEN, 1)
        w.seed { it.copy(alerts = mapOf("a" to alert)) }

        val summary = GetSummary(w.devices, w.alerts, w.restaurants, w.tx, w.clock)()
        assertThat(summary.devicesTotal).isEqualTo(3)
        assertThat(summary.devicesOnline).isEqualTo(1)
        assertThat(summary.devicesOffline).isEqualTo(2)
        assertThat(summary.offlineLastHour).isEqualTo(1)
        assertThat(summary.alertsOpen).isEqualTo(1)
        assertThat(summary.problems.single().devicesTotal).isEqualTo(3)
    }

    private fun restaurant(id: String, code: String) =
        Restaurant(RestaurantId(id), code, "name $code", "city", "addr", "Europe/Moscow", 1, 1)

    @Suppress("LongParameterList")
    private fun view(
        id: String,
        rule: RuleId,
        device: String,
        restaurant: Restaurant,
        openedAt: Long,
        target: String? = null,
        details: Map<String, String> = emptyMap(),
        suppressed: Boolean = false,
        state: AlertState = AlertState.OPEN,
    ) = AlertView(
        Alert(
            id, AlertSubject(rule, DeviceId.parse(device)!!, target), restaurant.id, state, openedAt,
            parentIncidentId = if (suppressed) "i-1" else null, details = details,
        ),
        "name $device", restaurant.code, restaurant.name,
    )
}
