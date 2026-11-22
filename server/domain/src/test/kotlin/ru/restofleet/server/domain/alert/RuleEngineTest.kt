package ru.restofleet.server.domain.alert

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import ru.restofleet.server.domain.alert.RuleInput.Event
import ru.restofleet.server.domain.alert.RuleInput.StatusChanged
import ru.restofleet.server.domain.alert.RuleInput.Telemetry
import ru.restofleet.server.domain.ingest.DeviceSignal
import ru.restofleet.server.domain.restaurant.RestaurantId
import ru.restofleet.shared.kernel.DeviceId

/**
 * Табличные тесты правил R-01…R-06 (план 2.1): строка — прежние факты и тревоги, вход и ожидаемые решения.
 * Решение записывается строкой: `open R-03` / `open R-05 api`, `resolve <id>`; факты — `R-02/charging`.
 */
class RuleEngineTest {

    private data class Row(
        val name: String,
        val input: RuleInput,
        val facts: Set<String> = emptySet(),
        val open: List<Alert> = emptyList(),
        val expected: List<String> = emptyList(),
        val expectedFacts: Set<String>? = null,
        val expectedDetails: Map<String, String>? = null,
    )

    private val rows = listOf(
        // R-01: офлайн и онлайн
        Row("R-01 offline opens", offline(), expected = listOf("open R-01"), expectedFacts = setOf("R-01/offline"),
            expectedDetails = mapOf("reason" to "lwt")),
        Row("R-01 repeated offline is absorbed", offline(), facts = setOf("R-01/offline"),
            open = listOf(alert(RuleId.R01))),
        Row("R-01 online resolves automatically", online(), facts = setOf("R-01/offline"),
            open = listOf(alert(RuleId.R01)), expected = listOf("resolve a-R-01"), expectedFacts = emptySet()),
        Row("R-01 online without alert changes nothing", online()),
        Row("R-01 offline after manual resolve while still offline does not reopen", offline(),
            facts = setOf("R-01/offline")),
        Row("R-01 offline without reason", StatusChanged(D, T, online = false), expected = listOf("open R-01"),
            expectedDetails = emptyMap()),
        // R-02: battery_low от агента и зарядка по телеметрии
        Row("R-02 19 % not charging opens", battery(19.0, enter = true),
            expected = listOf("open R-02"), expectedDetails = mapOf("batteryLevel" to "19", "limit" to "20")),
        Row("R-02 20 % enter opens (agent: <= limit)", battery(20.0, enter = true), expected = listOf("open R-02")),
        Row("R-02 enter while charging does not open", battery(19.0, enter = true), facts = setOf("R-02/charging"),
            expectedFacts = setOf("R-02/charging", "R-02/battery_low")),
        Row("R-02 charging stops below limit opens", telemetry(charging = false),
            facts = setOf("R-02/charging", "R-02/battery_low"), expected = listOf("open R-02"),
            expectedFacts = setOf("R-02/battery_low")),
        Row("R-02 charging starts resolves", telemetry(charging = true), facts = setOf("R-02/battery_low"),
            open = listOf(alert(RuleId.R02)), expected = listOf("resolve a-R-02")),
        Row("R-02 21 % exit resolves", battery(22.0, enter = false), facts = setOf("R-02/battery_low"),
            open = listOf(alert(RuleId.R02)), expected = listOf("resolve a-R-02"), expectedFacts = emptySet()),
        Row("R-02 telemetry without charging flag changes nothing", telemetry(charging = null),
            facts = setOf("R-02/battery_low"), open = listOf(alert(RuleId.R02))),
        // R-03: temp_high и thermal — одна тревога
        Row("R-03 45.1 °C temp_high opens", threshold(DeviceSignal.TEMP_HIGH, 45.1, 45.0, enter = true),
            expected = listOf("open R-03"), expectedDetails = mapOf("tempC" to "45.1", "limit" to "45.0")),
        Row("R-03 thermal SEVERE opens", threshold(DeviceSignal.THERMAL, 3.0, 3.0, enter = true),
            expected = listOf("open R-03"), expectedDetails = mapOf("thermal" to "SEVERE", "limit" to "SEVERE")),
        Row("R-03 second facet while open adds nothing", threshold(DeviceSignal.THERMAL, 4.0, 3.0, enter = true),
            facts = setOf("R-03/temp_high"), open = listOf(alert(RuleId.R03)),
            expectedFacts = setOf("R-03/temp_high", "R-03/thermal")),
        Row("R-03 one facet exits, other holds", threshold(DeviceSignal.TEMP_HIGH, 43.0, 45.0, enter = false),
            facts = setOf("R-03/temp_high", "R-03/thermal"), open = listOf(alert(RuleId.R03)),
            expectedFacts = setOf("R-03/thermal")),
        Row("R-03 thermal MODERATE exit resolves last facet", threshold(DeviceSignal.THERMAL, 2.0, 3.0, enter = false),
            facts = setOf("R-03/thermal"), open = listOf(alert(RuleId.R03)), expected = listOf("resolve a-R-03")),
        Row("R-03 unknown thermal code is kept as number", threshold(DeviceSignal.THERMAL, 9.0, 3.5, enter = true),
            expectedDetails = mapOf("thermal" to "9", "limit" to "3.5"), expected = listOf("open R-03")),
        Row("unknown threshold metric is ignored", threshold("humidity", 1.0, 1.0, enter = true)),
        // R-04: fg_absent, затем fg_changed на отслеживаемое приложение
        Row("R-04 fg_absent opens per package", fgAbsent(CHROME), expected = listOf("open R-04 $CHROME"),
            expectedDetails = mapOf(
                "pkg" to CHROME, "foregroundApp" to "com.android.settings", "sinceTs" to "100", "limitMin" to "10",
            )),
        Row("R-04 fg_changed to watched app resolves", fgChanged(CHROME), facts = setOf("R-04/fg_absent/$CHROME"),
            open = listOf(alert(RuleId.R04, CHROME)), expected = listOf("resolve a-R-04")),
        Row("R-04 fg_changed to other app keeps alert", fgChanged("com.other"), facts = setOf("R-04/fg_absent/$CHROME"),
            open = listOf(alert(RuleId.R04, CHROME))),
        Row("R-04 fg_changed to nothing is ignored", Event(D, T, DeviceSignal.FgChanged(null)),
            facts = setOf("R-04/fg_absent/$CHROME")),
        // R-05: probe_state по цели
        Row("R-05 api down opens", probe("api", down = true), expected = listOf("open R-05 api"),
            expectedDetails = mapOf("error" to "timeout", "failures" to "3")),
        Row("R-05 kds down is a separate subject", probe("kds", down = true), facts = setOf("R-05/down/api"),
            open = listOf(alert(RuleId.R05, "api")), expected = listOf("open R-05 kds")),
        Row("R-05 api up resolves only api", probe("api", down = false),
            facts = setOf("R-05/down/api", "R-05/down/kds"),
            open = listOf(alert(RuleId.R05, "api"), alert(RuleId.R05, "kds", id = "a-kds")),
            expected = listOf("resolve a-R-05"), expectedFacts = setOf("R-05/down/kds")),
        // R-06: 499/500 МБ на входе, 599/600 МБ на выходе (гистерезис 100 МБ)
        Row("R-06 499 MB opens", telemetry(freeMb = 499), expected = listOf("open R-06"),
            expectedDetails = mapOf("freeMb" to "499", "limit" to "500")),
        Row("R-06 500 MB does not open", telemetry(freeMb = 500), expectedFacts = emptySet()),
        Row("R-06 599 MB keeps alert", telemetry(freeMb = 599), facts = setOf("R-06/low_storage"),
            open = listOf(alert(RuleId.R06))),
        Row("R-06 600 MB resolves", telemetry(freeMb = 600), facts = setOf("R-06/low_storage"),
            open = listOf(alert(RuleId.R06)), expected = listOf("resolve a-R-06"), expectedFacts = emptySet()),
        // Жизненный цикл: подтверждённая тревога закрывается автоматически так же
        Row("acknowledged alert resolves on exit", online(), facts = setOf("R-01/offline"),
            open = listOf(alert(RuleId.R01).copy(state = AlertState.ACKNOWLEDGED)),
            expected = listOf("resolve a-R-01")),
        Row("closed alert is not resolved again", online(), facts = setOf("R-01/offline"),
            open = listOf(alert(RuleId.R01).copy(state = AlertState.RESOLVED))),
        Row("stale open alert without fact is resolved on exit", online(), open = listOf(alert(RuleId.R01)),
            expected = listOf("resolve a-R-01")),
    )

    @Test
    fun table() {
        rows.forEach { row ->
            val conditions = row.facts.associate { fact ->
                val key = parseFact(fact)
                key to Condition(key, 1, emptyMap())
            }
            val outcome = RuleEngine.evaluate(row.input, conditions, row.open, RuleParams())
            assertWithMessage(row.name).that(outcome.changes.map(::describe)).containsExactlyElementsIn(row.expected)
            row.expectedFacts?.let { expected ->
                assertWithMessage("${row.name}: facts").that(outcome.conditions.keys.map(::describe))
                    .containsExactlyElementsIn(expected)
            }
            row.expectedDetails?.let { expected ->
                val open = outcome.changes.filterIsInstance<AlertChange.Open>().single()
                assertWithMessage("${row.name}: details").that(open.details).isEqualTo(expected)
            }
        }
    }

    @Test
    fun newFactKeepsItsFirstTimeWhenRepeated() {
        val key = ConditionKey(RuleId.R01, D, null, Facets.OFFLINE)
        val outcome = RuleEngine.evaluate(offline(), mapOf(key to Condition(key, 1, emptyMap())), emptyList())
        assertWithMessage("since").that(outcome.conditions.getValue(key).since).isEqualTo(1)
        val fresh = RuleEngine.evaluate(offline(), emptyMap(), emptyList())
        assertWithMessage("since of new").that(fresh.conditions.getValue(key).since).isEqualTo(T)
    }

    @Test
    fun paramsRejectExitBelowEnter() {
        val error = runCatching { RuleParams(freeMbEnter = 600, freeMbExit = 500) }.exceptionOrNull()
        assertWithMessage("exit < enter").that(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun describe(change: AlertChange): String = when (change) {
        is AlertChange.Open -> listOfNotNull("open", change.subject.rule.code, change.subject.target).joinToString(" ")
        is AlertChange.AutoResolve -> "resolve ${change.alertId}"
    }

    private fun describe(key: ConditionKey): String =
        listOfNotNull(key.rule.code, key.facet, key.target).joinToString("/")

    private fun parseFact(fact: String): ConditionKey {
        val parts = fact.split("/")
        return ConditionKey(RuleId.fromCode(parts[0])!!, D, parts.getOrNull(2), parts[1])
    }

    private companion object {
        val D: DeviceId = DeviceId.parse("d-1")!!
        const val T = 1_000L
        const val CHROME = "com.android.chrome"

        fun alert(rule: RuleId, target: String? = null, id: String = "a-${rule.code}") = Alert(
            id = id, subject = AlertSubject(rule, D, target), restaurantId = RestaurantId("r-1"),
            state = AlertState.OPEN, openedAt = 1,
        )

        fun offline() = StatusChanged(D, T, online = false, reason = "lwt")
        fun online() = StatusChanged(D, T, online = true)
        fun telemetry(charging: Boolean? = null, freeMb: Long? = null) = Telemetry(D, T, charging, freeMb)
        fun threshold(metric: String, value: Double, limit: Double, enter: Boolean) =
            Event(D, T, DeviceSignal.Threshold(metric, enter, value, limit))

        fun battery(value: Double, enter: Boolean) = threshold(DeviceSignal.BATTERY_LOW, value, 20.0, enter)
        fun fgAbsent(pkg: String) = Event(D, T, DeviceSignal.FgAbsent(pkg, 100, 10, "com.android.settings"))
        fun fgChanged(to: String) = Event(D, T, DeviceSignal.FgChanged(to))
        fun probe(target: String, down: Boolean) =
            Event(D, T, DeviceSignal.ProbeState(target, down, if (down) "timeout" else null, if (down) 3 else 0))
    }
}
