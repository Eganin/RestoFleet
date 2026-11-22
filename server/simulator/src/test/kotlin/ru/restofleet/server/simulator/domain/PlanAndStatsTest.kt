package ru.restofleet.server.simulator.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.restofleet.server.simulator.domain.model.CounterSnapshot
import ru.restofleet.server.simulator.domain.model.DeviceRole.ASSEMBLY
import ru.restofleet.server.simulator.domain.model.DeviceRole.HALL
import ru.restofleet.server.simulator.domain.model.DeviceRole.KITCHEN
import ru.restofleet.server.simulator.domain.model.RoleMix
import ru.restofleet.server.simulator.domain.model.Slot
import ru.restofleet.server.simulator.domain.model.forRun
import ru.restofleet.server.simulator.domain.plan.DevicePlan
import ru.restofleet.server.simulator.domain.runtime.RunMetrics
import ru.restofleet.server.simulator.domain.runtime.minus
import ru.restofleet.server.simulator.domain.stats.Percentiles
import ru.restofleet.server.simulator.support.creds
import kotlin.test.assertFailsWith

class PlanAndStatsTest {
    @Test
    fun planGivesEachRestaurantTheMixAndWrapsAround() {
        val slots = DevicePlan.slots(listOf("r-b", "r-a"), RoleMix(), from = 0, until = 14)
        assertThat(slots.take(6)).containsExactly(
            Slot("r-a", KITCHEN), Slot("r-a", KITCHEN), Slot("r-a", HALL), Slot("r-a", HALL), Slot("r-a", HALL),
            Slot("r-a", ASSEMBLY),
        ).inOrder()
        assertThat(slots[6]).isEqualTo(Slot("r-b", KITCHEN))
        assertThat(slots[12]).isEqualTo(Slot("r-a", KITCHEN))
        assertThat(DevicePlan.slots(listOf("r-a", "r-b"), RoleMix(), from = 6, until = 7)).containsExactly(slots[6])
        val kitchens = DevicePlan.slots(listOf("r"), RoleMix(1, 0, 0), 0, 3).map { it.role }
        assertThat(kitchens).containsExactly(KITCHEN, KITCHEN, KITCHEN)
    }

    @Test
    fun planRejectsEmptyInput() {
        assertFailsWith<IllegalArgumentException> { DevicePlan.slots(emptyList(), RoleMix(), 0, 1) }
        assertFailsWith<IllegalArgumentException> { DevicePlan.slots(listOf("r"), RoleMix(), 2, 1) }
        assertFailsWith<IllegalArgumentException> { RoleMix(0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { RoleMix(-1, 1, 1) }
    }

    @Test
    fun percentilesUseNearestRank() {
        val values = (1..20).map { it.toDouble() }.shuffled(kotlin.random.Random(1))
        assertThat(Percentiles.of(values, 50.0)).isEqualTo(10.0)
        assertThat(Percentiles.of(values, 95.0)).isEqualTo(19.0)
        assertThat(Percentiles.of(values, 100.0)).isEqualTo(20.0)
        assertThat(Percentiles.of(listOf(3.0), 1.0)).isEqualTo(3.0)
        val summary = Percentiles.summary(values)!!
        assertThat(summary.count).isEqualTo(20)
        assertThat(summary.max).isEqualTo(20.0)
        assertThat(summary.mean).isEqualTo(10.5)
        assertThat(Percentiles.summary(emptyList())).isNull()
        assertFailsWith<IllegalArgumentException> { Percentiles.of(emptyList(), 50.0) }
        assertFailsWith<IllegalArgumentException> { Percentiles.of(values, 0.0) }
    }

    @Test
    fun metricsCountAndSubtract() {
        val metrics = RunMetrics()
        val start = metrics.snapshot()
        metrics.connected()
        metrics.connected()
        metrics.disconnected()
        metrics.connectFailed()
        metrics.connectionLost()
        metrics.messageOut(10)
        metrics.messageIn(5)
        metrics.request(3, 7)
        metrics.requestFailed()
        metrics.publishFailed()
        assertThat(metrics.snapshot() - start).isEqualTo(
            CounterSnapshot(
                connected = 1, connectFailures = 1, connectionLosses = 1, messagesOut = 1, messagesIn = 1,
                requests = 1, requestErrors = 1, publishErrors = 1, payloadBytesOut = 13, payloadBytesIn = 12,
            ),
        )
    }

    @Test
    fun runTakesFirstDevicesAndHidesPasswords() {
        val all = (1..3).map { creds(it) }
        assertThat(all.forRun(2)).containsExactly(all[0], all[1]).inOrder()
        assertFailsWith<IllegalArgumentException> { all.forRun(4) }
        assertThat(all[0].toString()).doesNotContain("pw-1")
    }
}
