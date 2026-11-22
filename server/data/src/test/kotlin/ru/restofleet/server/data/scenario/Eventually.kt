package ru.restofleet.server.data.scenario

/** Ждёт выполнения [condition] (опрос раз в 50 мс); по таймауту — последняя проверка как есть. */
internal fun eventually(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(50)
    }
    return condition()
}
