package ru.restofleet.agent.data.lifecycle

/** Фейк wake lock: пишет события `hold(<timeout>)`/`release` по порядку. */
class FakeCpuWakeLock : CpuWakeLock {
    val events = mutableListOf<String>()
    override var isHeld: Boolean = false
        private set

    override fun hold(timeoutMs: Long) {
        isHeld = true
        events += "hold($timeoutMs)"
    }

    override fun release() {
        isHeld = false
        events += "release"
    }
}
