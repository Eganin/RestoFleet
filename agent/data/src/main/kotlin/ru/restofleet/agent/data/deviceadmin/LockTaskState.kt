package ru.restofleet.agent.data.deviceadmin

/** Состояние lock task устройства (киоск D4 или закрепление экрана). */
interface LockTaskState {
    /** Устройство сейчас в lock task. */
    fun isActive(): Boolean

    /** Пакеты, которым lock task разрешает показывать окна (allowlist DPM); пуст, если неизвестен. */
    fun allowedPackages(): Set<String>
}
