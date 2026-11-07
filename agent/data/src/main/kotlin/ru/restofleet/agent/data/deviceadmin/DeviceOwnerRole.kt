package ru.restofleet.agent.data.deviceadmin

import android.app.admin.DevicePolicyManager

/** Единый источник ответа «приложение — Device Owner»: им пользуются адаптер DO и коллекторы R10/R13. */
fun interface DeviceOwnerRole {
    fun isDeviceOwner(): Boolean
}

/** Роль по `DevicePolicyManager`; проверяется при каждом обращении, потому что роль можно снять на лету. */
class DpmDeviceOwnerRole(
    private val dpm: DevicePolicyManager,
    private val packageName: String,
) : DeviceOwnerRole {
    override fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(packageName)
}
