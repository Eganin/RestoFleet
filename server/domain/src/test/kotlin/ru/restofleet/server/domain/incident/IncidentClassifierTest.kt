package ru.restofleet.server.domain.incident

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.DeviceId

/**
 * Табличные тесты классификатора (план 3.1; спецификация 4.2; реш. 409, 410): строка — рестораны окна и ожидаемые
 * инциденты (`R-07 r1`, `R-08 r1,r2`, `R-09 r3`) и пороги `n`/`m`.
 */
class IncidentClassifierTest {

    private data class Row(
        val name: String,
        val stats: List<RestaurantStats>,
        val expected: Set<String>,
        val n: Int? = null,
        val m: Int? = null,
    )

    private val rows = listOf(
        // S8 (а): глобальный отказ бэкенда — один инцидент со всеми ресторанами
        Row("S8a global outage", down(1..12), setOf("R-08 " + (1..12).joinToString(",") { id(it) }), n = 12, m = 3),
        // S8 (б): сеть одного ресторана — R-07 только для него
        Row("S8b network by offline devices", healthy(11) + r(12, online = 2), setOf("R-07 ${id(12)}")),
        Row("S8b network by ref down from every online device", healthy(11) + r(12, apiDown = 6, refUp = 0),
            setOf("R-07 ${id(12)}")),
        // S8 (в): бэкенд недоступен из двух ресторанов при живом ref
        Row("S8c two restaurants is local", healthy(10) + r(11, apiDown = 6) + r(12, apiDown = 6),
            setOf("R-09 ${id(11)}", "R-09 ${id(12)}"), n = 12, m = 3),
        // P = 49 / 50 / 51 %
        Row("P 49% is not down", healthy(11) + r(12, total = 100, online = 100, apiOnline = 100, apiDown = 49),
            emptySet()),
        Row("P 50% is down", healthy(11) + r(12, total = 100, online = 100, apiOnline = 100, apiDown = 50),
            setOf("R-09 ${id(12)}")),
        Row("P 51% is down", healthy(11) + r(12, total = 100, online = 100, apiOnline = 100, apiDown = 51),
            setOf("R-09 ${id(12)}")),
        // |A| = 2, 3 при N = 12 (M = 3); M−1 = 9 и M = 10 при N = 40
        Row("N12 |A|=2 local", healthy(10) + down(11..12), (11..12).map { "R-09 ${id(it)}" }.toSet(), n = 12, m = 3),
        Row("N12 |A|=3 global", healthy(9) + down(10..12), setOf("R-08 " + (10..12).joinToString(",") { id(it) }),
            n = 12, m = 3),
        Row("N40 |A|=M-1 local", healthy(31) + down(32..40), (32..40).map { "R-09 ${id(it)}" }.toSet(), n = 40, m = 10),
        Row("N40 |A|=M global", healthy(30) + down(31..40), setOf("R-08 " + (31..40).joinToString(",") { id(it) }),
            n = 40, m = 10),
        // Пограничные случаи реш. 410
        Row("single device restaurant offline is R-01 only", healthy(11) + r(12, total = 1, online = 0), emptySet()),
        Row("half offline of two devices is network", healthy(11) + r(12, total = 2, online = 1, apiOnline = 1),
            setOf("R-07 ${id(12)}")),
        Row("ref down on one of two online is not network", healthy(11) + r(12, refOnline = 2, refUp = 1), emptySet()),
        Row("no online device with api data: not in A, not in N", healthy(11) + r(12, apiOnline = 0, apiDown = 0),
            emptySet(), n = 11, m = 3),
        Row("api down without ref data is unknown, not backend", healthy(11) + r(12, apiDown = 6, refOnline = 0),
            emptySet()),
        Row("network restaurant is not counted in A", healthy(9) + r(10, online = 2, apiOnline = 2, apiDown = 2) +
            down(11..12), setOf("R-07 ${id(10)}", "R-09 ${id(11)}", "R-09 ${id(12)}")),
        Row("all healthy", healthy(12), emptySet(), n = 12, m = 3),
        Row("empty fleet", emptyList(), emptySet(), n = 0, m = 3),
    )

    @Test
    fun table() {
        rows.forEach { row ->
            val result = IncidentClassifier.classify(row.stats)
            assertWithMessage(row.name).that(result.incidents.map(::describe).toSet()).isEqualTo(row.expected)
            row.n?.let { assertWithMessage("${row.name}: n").that(result.thresholds.n).isEqualTo(it) }
            row.m?.let { assertWithMessage("${row.name}: m").that(result.thresholds.m).isEqualTo(it) }
            assertWithMessage("${row.name}: p").that(result.thresholds.p).isEqualTo(50)
        }
    }

    @Test
    fun mFormulaAndParamValidation() {
        val params = ClassifierParams()
        assertThat(listOf(0, 1, 12, 13, 38, 40, 41).map(params::m)).containsExactly(3, 3, 3, 4, 10, 10, 11).inOrder()
        assertThat(runCatching { ClassifierParams(backendDownPct = 0) }.isFailure).isTrue()
        assertThat(runCatching { ClassifierParams(minRestaurants = 0) }.isFailure).isTrue()
    }

    @Test
    fun aggregatorCountsOnlyOnlineCountedDevicesAndFreshObservations() {
        val r1 = RestaurantId("r-01")
        val fleet = listOf(
            FleetDevice(dev(1), r1, online = true), FleetDevice(dev(2), r1, online = true),
            FleetDevice(dev(3), r1, online = false),
        )
        val observations = listOf(
            ProbeObservation(dev(1), "api", down = true, error = "timeout"),
            ProbeObservation(dev(1), "api", down = true, error = "timeout"),
            ProbeObservation(dev(2), "api", down = true, error = "dns_fail"),
            ProbeObservation(dev(2), "ref", down = false, error = null),
            ProbeObservation(dev(3), "api", down = true, error = "timeout"),
            ProbeObservation(dev(9), "api", down = true, error = "timeout"),
        )
        val stats = ProbeAggregator.aggregate(fleet, observations).single()
        assertThat(stats.devicesTotal).isEqualTo(3)
        assertThat(stats.devicesOnline).isEqualTo(2)
        assertThat(stats.target("api")).isEqualTo(TargetStats(2, 2, mapOf("timeout" to 1, "dns_fail" to 1)))
        assertThat(stats.target("ref")).isEqualTo(TargetStats(1, 0, emptyMap()))
        assertThat(stats.target("kds")).isEqualTo(TargetStats(0, 0, emptyMap()))
        assertThat(stats.target("ref").up).isEqualTo(1)
    }

    private fun describe(c: Classified) = "${c.kind.rule.code} ${c.restaurants.joinToString(",") { it.value }}"

    private companion object {
        fun id(n: Int) = "r-%02d".format(n)
        fun dev(n: Int) = DeviceId.parse("d-$n")!!

        /** Ресторан: [total] учитываемых, [online] онлайн; по `api` и `ref` — онлайн с данными и недоступных. */
        @Suppress("LongParameterList")
        fun r(
            n: Int,
            total: Int = 6,
            online: Int = 6,
            apiOnline: Int = online,
            apiDown: Int = 0,
            refOnline: Int = online,
            refUp: Int = refOnline,
        ) = RestaurantStats(
            RestaurantId(id(n)), total, online,
            buildMap {
                if (apiOnline > 0) put("api", TargetStats(apiOnline, apiDown, mapOf("timeout" to apiDown)))
                if (refOnline > 0) put("ref", TargetStats(refOnline, refOnline - refUp, emptyMap()))
            },
        )

        fun healthy(count: Int) = (1..count).map { r(it) }
        fun down(range: IntRange) = range.map { r(it, apiDown = 6) }
    }
}
