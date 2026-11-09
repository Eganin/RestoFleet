package ru.restofleet.agent.data.deviceadmin

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName

/**
 * Состояние lock task по `ActivityManager.lockTaskModeState` (любой режим, кроме `NONE`: киоск D4 и закрепление
 * экрана). Allowlist читается у DPM только для Device Owner; `SecurityException` при потере роли даёт пустой список.
 */
class AndroidLockTaskState(
    private val dpm: DevicePolicyManager,
    private val admin: ComponentName,
    private val role: DeviceOwnerRole,
    private val lockTaskModeState: () -> Int,
) : LockTaskState {
    override fun isActive(): Boolean = lockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE

    @Suppress("SwallowedException") // SecurityException означает потерю роли: список неизвестен
    override fun allowedPackages(): Set<String> =
        if (role.isDeviceOwner()) {
            try {
                dpm.getLockTaskPackages(admin).toSet()
            } catch (e: SecurityException) {
                emptySet()
            }
        } else {
            emptySet()
        }
}
