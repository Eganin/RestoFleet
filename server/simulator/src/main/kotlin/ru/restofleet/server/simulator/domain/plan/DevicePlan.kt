package ru.restofleet.server.simulator.domain.plan

import ru.restofleet.server.simulator.domain.model.RoleMix
import ru.restofleet.server.simulator.domain.model.Slot

/**
 * Раскладка устройств по существующим ресторанам (`1-coursework.md` 4.6): ресторан получает набор [RoleMix]
 * (2 кухни, 3 зала, 1 сборка), следующий ресторан — следующий набор; когда рестораны кончились, раскладка идёт
 * по кругу (5 000 устройств на 40 ресторанов — по ≈ 21 набору на ресторан). Порядок детерминирован: слот `i` всегда
 * один и тот же, поэтому дорегистрация продолжает раскладку с того места, где она остановилась.
 */
object DevicePlan {
    fun slots(restaurants: List<String>, mix: RoleMix, from: Int, until: Int): List<Slot> {
        require(restaurants.isNotEmpty()) { "no restaurants on the stand: run seed first" }
        require(from in 0..until) { "invalid slot range $from..$until" }
        val pattern = mix.pattern()
        val ordered = restaurants.sorted()
        return (from until until).map { i ->
            Slot(ordered[(i / pattern.size) % ordered.size], pattern[i % pattern.size])
        }
    }

    /**
     * Слоты плана `0 until until`, которых ещё нет среди [cached] (V-9): сравнение по ресторану и роли, а не по числу
     * учёток. Если прошлый прогон потерял регистрацию в середине, дорегистрация закрывает именно эту дыру, и каждый
     * ресторан получает свой набор, а не 7 и 5.
     */
    fun missing(restaurants: List<String>, mix: RoleMix, cached: List<Slot>, until: Int): List<Slot> {
        val have = cached.groupingBy { it }.eachCount().toMutableMap()
        return slots(restaurants, mix, 0, until).filter { slot ->
            val left = have[slot] ?: 0
            if (left > 0) have[slot] = left - 1
            left == 0
        }
    }
}
