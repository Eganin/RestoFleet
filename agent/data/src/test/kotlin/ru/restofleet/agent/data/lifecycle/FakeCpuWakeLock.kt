package ru.restofleet.agent.data.lifecycle

/** Фейк с той же семантикой владения, что у `AndroidCpuWakeLock`; события пишет по порядку. */
class FakeCpuWakeLock : CpuWakeLock {
    val events = mutableListOf<String>()
    private var owner: CpuWakeLock.Lease? = null
    override val isHeld: Boolean get() = owner != null

    override fun acquire(timeoutMs: Long): CpuWakeLock.Lease {
        val lease = object : CpuWakeLock.Lease {
            override fun renew(timeoutMs: Long) {
                if (owner === this) events += "renew($timeoutMs)"
            }

            override fun release() {
                if (owner === this) {
                    owner = null
                    events += "release"
                }
            }
        }
        owner = lease
        events += "acquire($timeoutMs)"
        return lease
    }
}
