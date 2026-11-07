package ru.restofleet.agent.data.lifecycle

import android.content.Context
import android.os.PowerManager

/**
 * Удержание CPU, пока работает цикл агента: без него устройство с выключенным экраном засыпает, `delay` на
 * монотонных часах не идёт и тики телеметрии останавливаются. Удержание всегда с таймаутом и продлевается каждым
 * тиком, поэтому зависший цикл не держит CPU вечно.
 *
 * Замок общий на процесс, а циклы сменяются (пересоздание сервиса): им владеет последний [Lease]. Продление и
 * освобождение устаревшим [Lease] (цикл, который вытеснил новый) — no-op, поэтому запоздалый `release` старого цикла
 * не снимает замок нового.
 */
interface CpuWakeLock {
    val isHeld: Boolean

    /** Взять замок (или забрать у прежнего владельца) на [timeoutMs]; владельцем становится возвращённый [Lease]. */
    fun acquire(timeoutMs: Long): Lease

    interface Lease {
        /** Продлить удержание; если [Lease] вытеснен или освобождён — no-op. */
        fun renew(timeoutMs: Long)

        /** Отпустить; если [Lease] вытеснен или уже освобождён — no-op. */
        fun release()
    }
}

/** `PARTIAL_WAKE_LOCK` "RestoFleet:agent" без счётчика ссылок. Требует `WAKE_LOCK` в манифесте `agent:app`. */
class AndroidCpuWakeLock(context: Context) : CpuWakeLock {
    private val lock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
            .apply { setReferenceCounted(false) }

    private var owner: CpuWakeLock.Lease? = null

    override val isHeld: Boolean get() = lock.isHeld

    @Synchronized
    override fun acquire(timeoutMs: Long): CpuWakeLock.Lease {
        val lease = object : CpuWakeLock.Lease {
            override fun renew(timeoutMs: Long) = renewBy(this, timeoutMs)
            override fun release() = releaseBy(this)
        }
        owner = lease
        lock.acquire(timeoutMs)
        return lease
    }

    @Synchronized
    private fun renewBy(lease: CpuWakeLock.Lease, timeoutMs: Long) {
        if (owner === lease) lock.acquire(timeoutMs)
    }

    @Synchronized
    private fun releaseBy(lease: CpuWakeLock.Lease) {
        if (owner !== lease) return
        owner = null
        if (lock.isHeld) lock.release()
    }

    private companion object {
        const val TAG = "RestoFleet:agent"
    }
}
