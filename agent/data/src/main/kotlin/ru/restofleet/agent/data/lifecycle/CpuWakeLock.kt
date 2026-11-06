package ru.restofleet.agent.data.lifecycle

import android.content.Context
import android.os.PowerManager

/**
 * Удержание CPU, пока работает цикл агента: без него устройство с выключенным экраном засыпает, `delay` на
 * монотонных часах не идёт и тики телеметрии останавливаются. [hold] всегда с таймаутом и продлевается каждым тиком,
 * поэтому зависший цикл не держит CPU вечно.
 */
interface CpuWakeLock {
    val isHeld: Boolean

    /** Взять или продлить удержание: повторные вызовы не накапливаются (счётчика ссылок нет). */
    fun hold(timeoutMs: Long)

    /** Отпустить; без предшествующего [hold] — no-op. */
    fun release()
}

/** `PARTIAL_WAKE_LOCK` "RestoFleet:agent" без счётчика ссылок. Требует `WAKE_LOCK` в манифесте `agent:app`. */
class AndroidCpuWakeLock(context: Context) : CpuWakeLock {
    private val lock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
            .apply { setReferenceCounted(false) }

    override val isHeld: Boolean get() = lock.isHeld

    override fun hold(timeoutMs: Long) = lock.acquire(timeoutMs)

    override fun release() {
        if (lock.isHeld) lock.release()
    }

    private companion object {
        const val TAG = "RestoFleet:agent"
    }
}
